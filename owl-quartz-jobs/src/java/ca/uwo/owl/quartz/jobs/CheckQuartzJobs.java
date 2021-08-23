package ca.uwo.owl.quartz.jobs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import org.sakaiproject.api.app.scheduler.events.TriggerEvent;
import org.sakaiproject.api.app.scheduler.events.TriggerEventManager;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.email.api.EmailService;
import org.sakaiproject.emailtemplateservice.model.RenderedTemplate;
import org.sakaiproject.emailtemplateservice.service.EmailTemplateService;
import org.sakaiproject.entitybroker.DeveloperHelperService;

/**
 * Looks at the recent events of our favourite quartz jobs.
 * If any such jobs do not have a completed event within a certain threshold, an email is sent
 * 
 * @author bbailla2, plukasew, bjones86
 * 
 * 2012.08.01: plukasew - OQJ-?? - refactored to pull list of jobs, time thresholds, and email notifications from sakai.properties
 * 2017.01.17: bjones86 - OQJ-34 - port to Sakai 11, update to Spring 4 and Quartz 2.2
 *
 */
@Slf4j
public class CheckQuartzJobs implements Job
{
    private static final String HEARTBEAT_JOBLIST_SAKAI_PROPERTY = "owlquartzjobs.checkquartzjobs.heartbeat.joblist";
    private static final String[] HEARTBEAT_JOBLIST = ServerConfigurationService.getStrings(HEARTBEAT_JOBLIST_SAKAI_PROPERTY);
    private static final String HEARTBEAT_JOBTHRESHOLD_SAKAI_PROPERTY = "owlquartzjobs.checkquartzjobs.heartbeat.jobthreshold";
    private static final String[] HEARTBEAT_JOBTHRESHOLD = ServerConfigurationService.getStrings(HEARTBEAT_JOBTHRESHOLD_SAKAI_PROPERTY);
    private static final Map<String, Long> ALERT_THRESHOLD_MAP;
    private static final String SCHEDULED_INVOCATION_PREFIX = "Scheduled Invocation:";

    static // initialize the alert threshold map
    {
        ALERT_THRESHOLD_MAP = new HashMap<>(); // maps job name -> alert threshold (in hours)
        if (HEARTBEAT_JOBLIST != null && HEARTBEAT_JOBTHRESHOLD != null && HEARTBEAT_JOBLIST.length == HEARTBEAT_JOBTHRESHOLD.length)
        {
            for (int i = 0; i < HEARTBEAT_JOBLIST.length; ++i)
            {
                try
                {
                    Long threshold = Long.valueOf(HEARTBEAT_JOBTHRESHOLD[i]);
                    ALERT_THRESHOLD_MAP.put(HEARTBEAT_JOBLIST[i], threshold);
                }
                catch (NumberFormatException nfe)
                {
                    // log and skip
                    // OWLTODO: Try to notify via email that something is wrong
                    log.error("Unable to convert threshold value: {} into number.", HEARTBEAT_JOBTHRESHOLD[i]);
                }
            }
        }
    }

    // email template constants
    private static final String EMAIL_HEARTBEAT_TEMPLATE = "owlquartzjobs.checkquartzjobs.heartbeat";
    private static final String EMAIL_HEARTBEAT_TEMPLATE_XML_FILE = "heartbeatEmailTemplate.xml";
    private static final String EMAIL_HEARTBEAT_TEMPLATE_KEY_JOBS = "jobs";
    private static final String EMAIL_NO_REPLY_ADDRESS = "no-reply@uwo.ca";
    private static final String EMAIL_NOTIFICATION_LIST_SAKAI_PROPERTY = "owlquartzjobs.checkquartzjobs.email.notificationList";
    private static final String[] NOTIFICATION_LIST = ServerConfigurationService.getStrings(EMAIL_NOTIFICATION_LIST_SAKAI_PROPERTY);

    private List<String> notificationList;
    private List<InternetAddress> recipients;

    @Getter @Setter private TriggerEventManager triggerEventManager;
    @Getter @Setter private EmailTemplateService emailTemplateService;
    @Getter @Setter private EmailService emailService;
    @Getter @Setter private DeveloperHelperService developerHelperService;

    public void init()
    {
        notificationList = new ArrayList<>();
        if (NOTIFICATION_LIST != null)
        {
            notificationList = Arrays.asList(NOTIFICATION_LIST);
        }

        recipients = new ArrayList<>();
        for (String email : notificationList)
        {
            try
            {
                recipients.add(new InternetAddress(email));
            }
            catch (AddressException ex)
            {
                // skip for now, we'll check the status of recipients later
            }
        }

        EmailTemplateHelper.loadTemplate(EMAIL_HEARTBEAT_TEMPLATE_XML_FILE, EMAIL_HEARTBEAT_TEMPLATE);
    }

    @Override
    public void execute( JobExecutionContext jobExecutionContext ) throws JobExecutionException
    {
        // check prerequisites and abort early if needed
        if (recipients.isEmpty())
        {
            // OWLTODO: Try to send email notification that something is wrong
            log.error("Email recipients list is empty, aborting job. Check sakai.properties.");
            return;
        }

        // check heartbeat of various jobs defined in sakai.properties
        heartMonitor();
        
        // other checks can go here
    }

    /**
     * Runs the "heartbeat" job checks that just see if the job has run within
     * its alert threshold
     */
    private void heartMonitor()
    {
        // check prequisites and abort early if needed
        if (ALERT_THRESHOLD_MAP.isEmpty())
        {
            // OWLTODO: Try to send email notification that something is wrong
            log.error("Heartbeat job/threshold map is empty, aborting job. Check sakai.properties.");
            return;
        }

        //This maps each job to a boolean - true if a notification needs to be sent out for that job
        HashMap<String, Boolean> jobNotify = new HashMap<>();

        //assume none of the jobs ran
        ALERT_THRESHOLD_MAP.keySet().forEach(job -> jobNotify.put(job, Boolean.TRUE));

        //get the all the relevant trigger events
        //earliest date we care about
        long maxThresholdMillis = Collections.max(ALERT_THRESHOLD_MAP.values()) * 60 * 60 * 1000; // hours -> milliseconds
        Date minDate = new Date(System.currentTimeMillis()-maxThresholdMillis);
        //latest date we care about
        Date maxDate = new Date (System.currentTimeMillis());
        //the trigger event types we care about
        TriggerEvent.TRIGGER_EVENT_TYPE[] triggerEventTypes = new TriggerEvent.TRIGGER_EVENT_TYPE[]{TriggerEvent.TRIGGER_EVENT_TYPE.COMPLETE};

        List<TriggerEvent> events = triggerEventManager.getTriggerEvents(minDate, maxDate, null, null, triggerEventTypes);
        List<TriggerEvent> targettedEvents = events.stream()
                .filter( e -> ALERT_THRESHOLD_MAP.keySet().contains( e.getJobName() ) || e.getJobName().startsWith( SCHEDULED_INVOCATION_PREFIX ) )
                .collect( Collectors.toList() );

        //Iterate over the events and figure out if they've completed within the expected threshold
        for( TriggerEvent event : targettedEvents )
        {
            String jobName = event.getJobName();

            // If job name starts with scheduled invocation prefix, the job name in sakai.propertis IS the prefix
            if (jobName != null && jobName.startsWith(SCHEDULED_INVOCATION_PREFIX))
            {
                jobName = SCHEDULED_INVOCATION_PREFIX;
            }

            Date date = event.getTime();
            //time elapsed since the job completed:
            long millisPassed = System.currentTimeMillis()-date.getTime();
            //time that millisPassed must be less than:
            Long threshold = ALERT_THRESHOLD_MAP.get(jobName);
            if (threshold != null)
            {
                long thresholdMillis = threshold * 60 * 60 * 1000; // hours -> milliseconds
                if (millisPassed < thresholdMillis) 
                {
                    //the job has completed, no need to notify
                    jobNotify.put(jobName, Boolean.FALSE);
                }
            }
        }

        //get a short list of jobs that need to be included in the email
        List<String> jobsToEmail = new ArrayList<>();
        Iterator<String> it = ALERT_THRESHOLD_MAP.keySet().iterator();
        while (it.hasNext())
        {
            String jobName = (String) it.next();
            //add jobName if we need to notify jobName
            if (jobNotify.get(jobName))
            {
                jobsToEmail.add(jobName + " (" + ALERT_THRESHOLD_MAP.get(jobName) + ")");
            }
        }

        //send the email (if 1+ jobs didn't run)
        if (!jobsToEmail.isEmpty())
        {
            // OWLTODO: Refactor this email attempt/fallback code into its own reusable method
            Map<String, String> replacementValues = new HashMap<>();
            replacementValues.put(EMAIL_HEARTBEAT_TEMPLATE_KEY_JOBS, jobsToEmail.toString());

            try
            {
                RenderedTemplate template = emailTemplateService.getRenderedTemplate(EMAIL_HEARTBEAT_TEMPLATE, Locale.ENGLISH, replacementValues);
                emailService.sendMail(new InternetAddress(EMAIL_NO_REPLY_ADDRESS), recipients.toArray(new InternetAddress[0]), template.getRenderedSubject(),
                                        template.getRenderedMessage(), null, null, null);

            }
            catch (Exception e)
            {
                // fall back to email template service and hardcoded email addresses
                List<String> toAddresses = new ArrayList<>();
                toAddresses.add(developerHelperService.getUserRefFromUserEid("bbailla2"));
                toAddresses.add(developerHelperService.getUserRefFromUserEid("bjones86"));
                toAddresses.add(developerHelperService.getUserRefFromUserEid("plukasew"));
                toAddresses.add(developerHelperService.getUserRefFromUserEid("sfoster9"));

                emailTemplateService.sendRenderedMessages(EMAIL_HEARTBEAT_TEMPLATE,toAddresses,replacementValues,EMAIL_NO_REPLY_ADDRESS, "Sakai Owl quartz job checker");
            }
        }
    } // end heartMonitor()
} // end class
