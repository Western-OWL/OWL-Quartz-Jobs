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

	private static final String REPORT_DIR_NAME = "finalGradesReports";
	private static final String REPORT_FILE_NAME = "finalGradesReport-";
	private static final String REPORT_ERRORS_SUFFIX = "-ERRORS.txt";
	private static final String REPORT_FILE_EXT = ".csv";
	private static final String[] REPORT_HEADER_ROW = { "Site Title", "Site ID", "Section Title", "Department Title", "Department Description", "# Revised", "# Added", "# Removed"};

	public void init()
	{
		// OWLTODO: any init here
	}

	@Override
	public void execute(JobExecutionContext ctx) throws JobExecutionException
	{
		var data = new HashMap<ReportKey, ReportData>(1000);  // OWLTODO: revise sizing with real numbers for qat/prd
		var errors = new ArrayList<String>();

		// site iteration and data gathering goes here (build up Report)
		// Loop through a list of all course sites
		List<Site> sites = ss.getSites(SiteService.SelectionType.ANY, "course", null, null, SiteService.SortType.NONE, null);
		for (Site site : sites)
		{
			// Get the realm ID of the site; get the sections for the site
			String realmID = ss.siteReference(site.getId());
			Set<String> sectionIDs = ags.getProviderIds(realmID);
			SiteData siteData = new SiteData(site.getId(), site.getTitle());
			for (String secID : sectionIDs)
			{
				ReportKey key = new ReportKey(secID, site.getId());

				// OWLTODO: get section/dept data from services
				SecData secData = new SecData("1", "Fake Section 1", "Fake Dept", "This is a fake department");

				// OWLTODO: get grade data from services
				FGData fg = new FGData(9, 1, 5);

				ReportData rd = new ReportData(fg, siteData, secData);
				data.put(key, rd);
			}
		}


		// Dummy data; OWLTODO: remove this when we have services generating this data
		errors.add("Fake error");
		// End dummy data

		// Create the output dir as needed; if we can't create it, log and abort
		try
		{
			checkOutputDir();
		}
		catch (Exception e)
		{
			log.error("Unable to create output directory; aborting!");
			return;
		}

		// outputFile compilation and saving goes here (consume Report)
		var report = new Report(data, errors);
		long timestamp = System.currentTimeMillis(); // Use the same timestamp for both files
		outputReport(report, timestamp);
		outputErrors(errors, timestamp);
	}


	/**
	 * Checks for the existence of the output directory, and creates it if necessary.
	 * Exceptions are left to bubble so we can catch and abort in the event the directory
	 * could not be created.
	 */
	private void checkOutputDir()
	{
		String outputDir = scs.getSakaiHomePath() + REPORT_DIR_NAME;
		File dir = new File(outputDir);
		if (!dir.exists())
		{
			dir.mkdirs();
			log.debug("Created output dir: {}", outputDir);
		}
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
