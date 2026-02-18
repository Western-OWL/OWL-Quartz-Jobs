package ca.uwo.owl.quartz.jobs;

import com.opencsv.CSVWriter;
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

/**
 * Scans all course sites and compiles an outputFile of the number of unsubmitted final grades by section.
 * @author plukasew
 * @author bjones86
 */
@Slf4j
public class FinalGradesReport implements Job
{
	@Getter @Setter private ServerConfigurationService scs;

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
		// OWLTODO: impl

		var data = new HashMap<ReportKey, ReportData>(1000);  // OWLTODO: revise sizing with real numbers for qat/prd
		var errors = new ArrayList<String>();

		// Dummy data; OWLTODO: remove this when we have services generating this data
		errors.add("Fake error");
		ReportKey key = new ReportKey("1", "1");
		FGData fg = new FGData(9, 1, 5);
		SiteData site = new SiteData("1", "Fake Site 1");
		SecData sec = new SecData("1", "Fake Section 1", "Fake Dept", "This is a fake department");
		ReportData rd = new ReportData(fg, site, sec);
		data.put(key, rd);

		key = new ReportKey("2", "1");
		fg = new FGData(4, 6, 8);
		site = new SiteData("1", "Fake Site 1");
		sec = new SecData("2", "Fake Section 2", "Fake Dept", "This is a fake department");
		rd = new ReportData(fg, site, sec);
		data.put(key, rd);
		// End dummy data

		// site iteration and data gathering goes here (build up Report)

		var report = new Report(data, errors);

		// outputFile compilation and saving goes here (consume Report)
		long timestamp = System.currentTimeMillis(); // Use the same timestamp for both files
		outputReport(report, timestamp);
		outputErrors(errors, timestamp);
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
			String filePath = scs.getSakaiHomePath() + REPORT_FILE_NAME + timestamp + REPORT_ERRORS_SUFFIX;
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

		String filePath = scs.getSakaiHomePath() + REPORT_FILE_NAME + timestamp + REPORT_FILE_EXT;
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
