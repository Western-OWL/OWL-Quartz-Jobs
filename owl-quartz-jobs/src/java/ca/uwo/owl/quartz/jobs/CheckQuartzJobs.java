package ca.uwo.owl.quartz.jobs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.HashMap;

import lombok.Getter;
import lombok.Setter;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import org.sakaiproject.api.app.scheduler.events.TriggerEvent;
import org.sakaiproject.api.app.scheduler.events.TriggerEventManager;

import org.sakaiproject.emailtemplateservice.service.EmailTemplateService;

import org.sakaiproject.entitybroker.DeveloperHelperService;

/**
 * Looks at the recent events of our favourite quartz jobs.
 * If any such jobs do not have a completed event within a certain threshold, an email is sent
 * @author bbailla2
 *
 */
public class CheckQuartzJobs implements Job 
{
	//Define the jobs we care about
	private final String SIS="SIS CSV Data Loader";
	private final String TIIRQ="TII Review Queue";
	private final String TIICRR="TII Content Review Reports";
	private final String TIICRRS="TII Content Review Roster Sync";
	
	//Keep the jobs we care about in a list
	private List<String> jobs=Arrays.asList(new String[]{
			SIS, 
			TIIRQ, 
			TIICRR, 
			TIICRRS});
	
	//This maps each job to the allowable amount of time before we should receive notifications
	private HashMap<String, Long> jobTimeThreshold = new HashMap();
	
	//This maps each job to a boolean - true iff a notification needs to be sent out for that job
	private HashMap<String, Boolean> jobNotify = new HashMap();
	
	//36h -> millis
	private final Long THIRTY_SIX_HOURS = new Long(129600000);
	
	@Getter @Setter private TriggerEventManager triggerEventManager;
	
	@Getter @Setter private EmailTemplateService emailTemplateService;
	
	@Getter @Setter private DeveloperHelperService developerHelperService;
	
	public void init()
	{
		
	}
	
	public void execute( JobExecutionContext jobExecutionContext ) throws JobExecutionException
	{
		//empty jobTimeThreshold and repopulate it (just in case it's still in memory from a previous run)
		jobTimeThreshold.clear();
		
		jobTimeThreshold.put(SIS, THIRTY_SIX_HOURS);
		jobTimeThreshold.put(TIIRQ, THIRTY_SIX_HOURS);
		jobTimeThreshold.put(TIICRR, THIRTY_SIX_HOURS);
		jobTimeThreshold.put(TIICRRS, THIRTY_SIX_HOURS);
		
		//empty jobNotify and repopulate it
		jobNotify.clear();
		
		//assume none of the jobs ran
		Iterator it = jobs.iterator();
		while (it.hasNext())
		{
			jobNotify.put((String) it.next(), Boolean.TRUE);
		}
		
		//get the all the relevant trigger events
		//earliest date we care about
		Date minDate = new Date(System.currentTimeMillis()-THIRTY_SIX_HOURS);
		//latest date we care about
		Date maxDate = new Date (System.currentTimeMillis());
		//the trigger event types we care about
		TriggerEvent.TRIGGER_EVENT_TYPE[] triggerEventTypes = new TriggerEvent.TRIGGER_EVENT_TYPE[]{TriggerEvent.TRIGGER_EVENT_TYPE.COMPLETE};
		
		List<TriggerEvent> events = triggerEventManager.getTriggerEvents(minDate, maxDate, jobs, null, triggerEventTypes);
		
		//Iterate over the events and figure out if they've completed within the expected threshold
		it = events.iterator();
		while (it.hasNext())
		{
			TriggerEvent event = (TriggerEvent) it.next();
			
			String jobName=event.getJobName();
			
			Date date = event.getTime();
			//time elapsed since the job completed:
			long millisPassed = System.currentTimeMillis()-date.getTime();
			//time that millisPassed must be less than:
			Long threshold = jobTimeThreshold.get(jobName);
			if (threshold != null)
			{
				if (millisPassed<threshold)
				{
					//the job has completed, no need to notify
					jobNotify.put(jobName, Boolean.FALSE);
				}
			}
		}
		
		//get a short list of jobs that need to be included in the email
		List<String> jobsToEmail = new ArrayList<String>();
		it = jobs.iterator();
		while (it.hasNext())
		{
			String jobName = (String) it.next();
			//add jobName if we need to notify jobName
			if (jobNotify.get(jobName))
			{
				jobsToEmail.add(jobName);
			}
		}
		
		//send the email (if 1+ jobs didn't run)
		if (!jobsToEmail.isEmpty())
		{
			List<String> toAddresses = new ArrayList<String>();
			toAddresses.add(developerHelperService.getUserRefFromUserEid("bbailla2"));
			toAddresses.add(developerHelperService.getUserRefFromUserEid("bjones86"));
			toAddresses.add(developerHelperService.getUserRefFromUserEid("plukasew"));
			toAddresses.add(developerHelperService.getUserRefFromUserEid("sfoster9"));
			HashMap replacementValues = new HashMap();
			replacementValues.put("jobs", jobsToEmail.toString());
			emailTemplateService.sendRenderedMessages("checkquartzjobs",toAddresses,replacementValues,"no-reply@uwo.ca", "Sakai Owl quartz job checker");
		}
	}
}
