package ca.uwo.owl.quartz.jobs;

import com.opencsv.CSVWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.authz.api.AuthzGroupService;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.coursemanagement.api.CourseOffering;
import org.sakaiproject.coursemanagement.api.CourseSet;
import org.sakaiproject.coursemanagement.api.Section;
import org.sakaiproject.coursemanagement.api.exception.IdNotFoundException;
import org.sakaiproject.service.gradebook.shared.GradebookNotFoundException;
import org.sakaiproject.service.gradebook.shared.GradebookService;
import org.sakaiproject.service.gradebook.shared.owl.OwlGradebookService;
import org.sakaiproject.service.gradebook.shared.owl.finalgrades.report.FGChanges;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;

/**
 * Scans all course sites and compiles a report of the number of unsubmitted final grades by section.
 * Files are output to <tomcatDir>/sakai/finalGradesReports/
 * Report files are named with the pattern: finalGradesReport-<unixTimestamp>.csv
 * Error files are named with the pattern: finalGradesReport-<unixTimestamp>-ERRORS.txt
 *
 * @author plukasew
 * @author bjones86
 */
@Slf4j
public class FinalGradesReport implements Job
{
	@Getter @Setter private ServerConfigurationService scs;
	@Getter @Setter private SiteService ss;
	@Getter @Setter private AuthzGroupService ags;
	@Getter @Setter private CourseManagementService cms;
	@Getter @Setter private GradebookService gs;
	private OwlGradebookService ogs;

	private static final String REPORT_DIR_NAME = "finalGradesReports";
	private static final String REPORT_FILE_NAME = "finalGradesReport-";
	private static final String REPORT_ERRORS_SUFFIX = "-ERRORS.txt";
	private static final String REPORT_FILE_EXT = ".csv";
	private static final String[] REPORT_HEADER_ROW = { "Site Title", "Site ID", "Section Title", "Department Title", "Department Description", "# Revised", "# Added", "# Removed" };

	public void init()
	{
		ogs = gs.owlDoNotCall();
	}

	@Override
	public void execute(JobExecutionContext ctx) throws JobExecutionException
	{
		// Create the output dir as needed; if it can't be created, no point in generating the report
		if (!checkOutputDir())
		{
			return;
		}

		// PRD non-deleted course sites: 83,535
		var data = new HashMap<ReportKey, ReportData>(/*83535*/);  // OWLTODO: uncomment this when ready for DEV/QAT deploy
		var errors = new ArrayList<String>();

		// Loop through a list of all course sites
		List<Site> sites = ss.getSites(SiteService.SelectionType.ANY, "course", null, null, SiteService.SortType.NONE, null);
		for (Site site : sites)
		{
			try
			{
				// Get the realm ID of the site; get the sections for the site
				String siteID = site.getId();
				String realmID = ss.siteReference(siteID);
				Set<String> sectionEIDs = ags.getProviderIds(realmID);
				for (String secEID : sectionEIDs)
				{
					Section sec = getSection(secEID);
					if (sec == null)
					{
						errors.add("Unable to get section by EID: " + secEID);
						continue;
					}

					CourseOffering offering = getCourseOffering(sec.getCourseOfferingEid());
					if (offering == null)
					{
						errors.add("Unable to get course offering by EID: " + sec.getCourseOfferingEid());
						continue;
					}

					Set<String> setEIDs = offering.getCourseSetEids();
					List<String> deptTitles = new ArrayList<>(setEIDs.size());
					List<String> deptDescriptions = new ArrayList<>(setEIDs.size());
					for (String setEID : setEIDs)
					{
						CourseSet set = getCourseSet(setEID);
						if (set == null)
						{
							errors.add("Unable to get course set by EID: " + setEID);
							continue;
						}

						deptTitles.add(set.getTitle());
						deptDescriptions.add(set.getDescription());
					}

					FGChanges fgc = ogs.getFinalGradeChanges(siteID, secEID);
					for (int i = 0; i < deptTitles.size(); i++)
					{
						SecData sd = new SecData(sec.getEid(), sec.getTitle(), deptTitles.get(i), deptDescriptions.get(i));
						ReportData rd = new ReportData(new FGData(fgc.revised, fgc.added, fgc.removed), new SiteData(siteID, site.getTitle()), sd);
						data.put(new ReportKey(secEID, siteID), rd);
					}
				}
			}
			catch (GradebookNotFoundException gnfe)
			{
				errors.add("Site " + site.getTitle() + " (" + site.getId() + ") has no gradebook.");
			}
		}

		// outputFile compilation and saving goes here (consume Report)
		long timestamp = System.currentTimeMillis(); // Use the same timestamp for both files
		outputReport(new Report(data, errors), timestamp);
		outputErrors(errors, timestamp);
	}

	private Section getSection(String sectionEID)
	{
		try { return cms.getSection(sectionEID); }
		catch (IdNotFoundException e)
		{
			log.error("Unable to get section by EID: {}", sectionEID);
			return null;
		}
	}

	private CourseOffering getCourseOffering(String offeringEID)
	{
		try { return cms.getCourseOffering(offeringEID); }
		catch (IdNotFoundException e)
		{
			log.error("Unable to get course offering by EID: {}", offeringEID);
			return null;
		}
	}

	private CourseSet getCourseSet(String setEID)
	{
		try { return cms.getCourseSet(setEID); }
		catch(IdNotFoundException e)
		{
			log.error("Unable to get course set by EID: {}", setEID);
			return null;
		}
	}

	/**
	 * Checks for the existence of the output directory, and creates it if necessary.
	 * @return true if the output directory exists or was created successfully; false otherwise
	 */
	private boolean checkOutputDir()
	{
		boolean success = true;
		String outputDir = scs.getSakaiHomePath() + REPORT_DIR_NAME;
		try
		{
			File dir = new File(outputDir);
			if (!dir.exists())
			{
				dir.mkdirs();
				log.debug("Created output dir: {}", outputDir);
			}
		}
		catch (Exception ex)
		{
			log.error("Unable to create output directory; aborting!");
			success = false;
		}

		return success;
	}

	/**
	 * If there are any errors encountered, output to /tomcat/sakai/finalGradesReport-<timestamp>-ERRORS.txt.
	 * Each line in the file is an error message.
	 *
	 * @param errors list of error messages
	 * @param timestamp unix timestamp to use for file naming
	 */
	private void outputErrors(List<String> errors, long timestamp)
	{
		if (CollectionUtils.isNotEmpty(errors))
		{
			String filePath = scs.getSakaiHomePath() + REPORT_DIR_NAME + File.separator + REPORT_FILE_NAME + timestamp + REPORT_ERRORS_SUFFIX;
			try
			{
				Path outputFile = Paths.get(filePath);
				Files.write(outputFile, errors, StandardCharsets.UTF_8);
				log.info("Final Grades Report errors file generated: {}", filePath);
			}
			catch (IOException ex)
			{
				log.error("Error creating file @ {}", filePath, ex);
			}
		}
	}

	/**
	 * Generate the Final Grades Report if any data was found to be reported on
	 * @param report Report data
	 * @param timestamp unix timestamp to use for file naming
	 */
	private void outputReport(Report report, long timestamp)
	{
		List<String[]> lines = report.data.values().stream().map(d -> new String[]{ d.site.title, d.site.id, d.sec.title, d.sec.deptTitle, d.sec.deptDesc, Integer.toString(d.fg.revised),
										Integer.toString(d.fg.added), Integer.toString(d.fg.removed)}).collect(Collectors.toList());
		if (lines.isEmpty())
		{
			log.info("No final grades to report!");
			return;
		}

		// Insert the header row
		lines.add(0, REPORT_HEADER_ROW);

		String filePath = scs.getSakaiHomePath() + REPORT_DIR_NAME + File.separator + REPORT_FILE_NAME + timestamp + REPORT_FILE_EXT;
		try (CSVWriter writer = new CSVWriter(new FileWriter(filePath)))
		{
			writer.writeAll(lines);
			log.info("Final Grades Report generated: {}", filePath);
		}
		catch (IOException ex)
		{
			log.error("Error creating file @ {}", filePath, ex);
		}
	}

	@AllArgsConstructor
	private static class FGData
	{
		private final int revised, added, removed;
	}

	@AllArgsConstructor
	private static class SiteData
	{
		private final String id, title;
	}

	@AllArgsConstructor
	private static class SecData
	{
		private final String eid, title, deptTitle, deptDesc;
	}

	@AllArgsConstructor
	private static class ReportData
	{
		private final FGData fg;
		private final SiteData site;
		private final SecData sec;
	}

	/**
	 * This class exists to support the remote possibility there are multiple sites with the same section.
	 * It combines the section eid with the site id to create a unique value. We use this as a map key to avoid
	 * having to create a list for every value.
	 */
	@AllArgsConstructor @EqualsAndHashCode
	private static class ReportKey implements Comparable<ReportKey>
	{
		private final String eid, id;

		@Override
		public String toString()
		{
			return eid + "::" + id;
		}

		@Override
		public int compareTo(ReportKey other)
		{
			return toString().compareTo(other.toString());
		}
	}

	@AllArgsConstructor
	private static class Report
	{
		public final Map<ReportKey, ReportData> data;
		public final List<String> errors;
	}
}
