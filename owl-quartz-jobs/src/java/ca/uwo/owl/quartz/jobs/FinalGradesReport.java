package ca.uwo.owl.quartz.jobs;

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
		// OWLTOOD: impl

		// site iteration and data gathering (build Map)

		// report compilation and saving (consume Map)
	}
}
