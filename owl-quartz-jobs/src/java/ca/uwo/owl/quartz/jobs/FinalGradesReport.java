package ca.uwo.owl.quartz.jobs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * Scans all course sites and compiles a report of the number of unsubmitted final grades by section.
 * @author plukasew
 * @author bjones86
 */
public class FinalGradesReport implements Job
{
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

		// site iteration and data gathering goes here (build up Report)

		var report = new Report(data, errors);

		// report compilation and saving goes here (consume Report)
	}

	private static class FGData
	{
		private final int revised, added, removed;

		public FGData(int rev, int add, int rem)
		{
			revised = rev;
			added = add;
			removed = rem;
		}
	}

	private static class SiteData
	{
		private final String id, title;

		public SiteData(String siteId, String siteTitle)
		{
			id = siteId;
			title = siteTitle;
		}
	}

	private static class SecData
	{
		private final String eid, title, deptTitle, deptDesc;

		public SecData(String id, String secTitle, String dTitle, String dDesc)
		{
			eid = id;
			title = secTitle;
			deptTitle = dTitle;
			deptDesc = dDesc;
		}
	}

	private static class ReportData
	{
		private final FGData fg;
		private final SiteData site;
		private final SecData sec;

		public ReportData(FGData fgData, SiteData siteData, SecData secData)
		{
			fg = fgData;
			site = siteData;
			sec = secData;
		}
	}

	/**
	 * This class exists to support the remote possibility there are multiple sites with the same section.
	 * It combines the section eid with the site id to create a unique value. We use this as a map key to avoid
	 * having to create a list for every value.
	 */
	private static class ReportKey implements Comparable<ReportKey>
	{
		private final String eid, id;

		public ReportKey(String secEid, String siteId)
		{
			eid = secEid;
			id = siteId;
		}

		@Override
		public String toString()
		{
			return eid + "::" + id;
		}

		@Override
		public int hashCode()
		{
			int hash = 17;
			hash = 53 * hash + Objects.hashCode(this.eid);
			hash = 53 * hash + Objects.hashCode(this.id);
			return hash;
		}

		@Override
		public boolean equals(Object obj)
		{
			if (this == obj)
			{
				return true;
			}
			if (obj == null)
			{
				return false;
			}
			if (getClass() != obj.getClass())
			{
				return false;
			}
			final ReportKey other = (ReportKey) obj;
			if (!Objects.equals(this.eid, other.eid))
			{
				return false;
			}
			return Objects.equals(this.id, other.id);
		}

		@Override
		public int compareTo(ReportKey other)
		{
			return toString().compareTo(other.toString());
		}
	}

	private static class Report
	{
		public final Map<ReportKey, ReportData> data;
		public final List<String> errors;

		public Report(Map<ReportKey, ReportData> rData, List<String> errs)
		{
			data = rData;
			errors = errs;
		}
	}
}
