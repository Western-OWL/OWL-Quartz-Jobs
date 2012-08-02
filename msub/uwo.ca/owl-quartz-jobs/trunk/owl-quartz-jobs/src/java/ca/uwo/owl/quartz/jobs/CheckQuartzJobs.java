// Unknown, bbailla2, New
// Looks at recent events for our favourite quartz jobs
// If any such jobs do not have a completed event within a certain threshold, an email is sent
// 2012.08.01, plukasew, Modified
// Refactored to pull list of jobs, time thresholds, and email notifications from sakai.properties

package ca.uwo.owl.quartz.jobs;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.*;
import java.util.logging.Level;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

import lombok.Getter;
import lombok.Setter;
import org.apache.log4j.Logger;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import org.sakaiproject.api.app.scheduler.events.TriggerEvent;
import org.sakaiproject.api.app.scheduler.events.TriggerEventManager;
import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.email.api.EmailService;
import org.sakaiproject.emailtemplateservice.model.EmailTemplate;
import org.sakaiproject.emailtemplateservice.model.RenderedTemplate;

import org.sakaiproject.emailtemplateservice.service.EmailTemplateService;

import org.sakaiproject.entitybroker.DeveloperHelperService;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.cover.SessionManager;

/**
 * Looks at the recent events of our favourite quartz jobs.
 * If any such jobs do not have a completed event within a certain threshold, an email is sent
 * @author bbailla2
 * @author plukasew
 *
 */
public class CheckQuartzJobs implements Job 
{
        private static final Logger log = Logger.getLogger(CheckQuartzJobs.class);
        private static final String LOG_PREFIX = "OWL: Check Quartz Jobs: ";
    
        private static final String HEARTBEAT_JOBLIST_SAKAI_PROPERTY = "owlquartzjobs.checkquartzjobs.heartbeat.joblist";
        private static final String[] heartbeatJobListArray = ServerConfigurationService.getStrings(HEARTBEAT_JOBLIST_SAKAI_PROPERTY);
        private static final String HEARTBEAT_JOBTHRESHOLD_SAKAI_PROPERTY = "owlquartzjobs.checkquartzjobs.heartbeat.jobthreshold";
        private static final String[] heartbeatJobThresholdArray = ServerConfigurationService.getStrings(HEARTBEAT_JOBTHRESHOLD_SAKAI_PROPERTY);
        private static final Map<String, Long> alertThresholdMap;
        static // initialize the alert threshold map
        {
            alertThresholdMap = new HashMap<String, Long>(); // maps job name -> alert threshold (in hours)
            if (heartbeatJobListArray != null && heartbeatJobThresholdArray != null && heartbeatJobListArray.length == heartbeatJobThresholdArray.length)
            {
                for (int i = 0; i < heartbeatJobListArray.length; ++i)
                {
                    try
                    {
                        Long threshold = Long.valueOf(heartbeatJobThresholdArray[i]);
                        alertThresholdMap.put(heartbeatJobListArray[i], threshold);
                    }
                    catch (NumberFormatException nfe)
                    {
                        // log and skip
                        // OWLTODO: Try to notify via email that something is wrong
                        log.error(LOG_PREFIX + "Unable to convert threshold value: " + heartbeatJobThresholdArray[i] + " into number.");
                    }
                }
            }
        }
        
        // email template constants
        private static final String ADMIN_ID 				= "admin";		// The login ID for the admin user
        private static final String TMPLT_ELMNT_SUBJECT		= "subject";			// The name of the subject element
	private static final String TMPLT_ELMNT_MESSAGE		= "message";			// The name of the message element
	private static final String TMPLT_ELMNT_HTML		= "messagehtml";		// The name of the message html element
	private static final String TMPLT_ELMNT_LOCALE		= "locale";			// The name of the locale element
	private static final String TMPLT_ELMNT_VERSION		= "version";			// The name of the version element
        private static final String EMAIL_HEARTBEAT_TEMPLATE = "owlquartzjobs.checkquartzjobs.heartbeat";
        private static final String EMAIL_HEARTBEAT_TEMPLATE_XML_FILE = "heartbeatEmailTemplate.xml";
        private static final String EMAIL_HEARTBEAT_TEMPLATE_KEY_JOBS = "jobs";
        private static final String EMAIL_NO_REPLY_ADDRESS = "no-reply@uwo.ca";
        private static final String EMAIL_NOTIFICATION_LIST_SAKAI_PROPERTY = "owlquartzjobs.checkquartzjobs.email.notificationList";
        private static final String[] notificationListArray = ServerConfigurationService.getStrings(EMAIL_NOTIFICATION_LIST_SAKAI_PROPERTY);
        
        private List<String> notificationList;
        private List<InternetAddress> recipients;
        
        @Getter @Setter private TriggerEventManager triggerEventManager;
	@Getter @Setter private EmailTemplateService emailTemplateService;
        @Getter @Setter private EmailService emailService;
	@Getter @Setter private DeveloperHelperService developerHelperService;
	
	public void init()
	{
            notificationList = new ArrayList<String>();
            if (notificationListArray != null)
            {
                notificationList = Arrays.asList(notificationListArray);
            }
            
            recipients = new ArrayList<InternetAddress>();
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
            
            loadTemplate(EMAIL_HEARTBEAT_TEMPLATE_XML_FILE, EMAIL_HEARTBEAT_TEMPLATE);
	}
	
	public void execute( JobExecutionContext jobExecutionContext ) throws JobExecutionException
	{
            // check prerequisites and abort early if needed
            if (recipients.isEmpty())
            {
                // OWLTODO: Try to send email notification that something is wrong
                log.error(LOG_PREFIX + "Email recipients list is empty, aborting job. Check sakai.properties.");
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
            if (alertThresholdMap.isEmpty())
            {
                // OWLTODO: Try to send email notification that something is wrong
                log.error(LOG_PREFIX + "Heartbeat job/threshold map is empty, aborting job. Check sakai.properties.");
                return;
            }
            
            //This maps each job to a boolean - true iff a notification needs to be sent out for that job
            HashMap<String, Boolean> jobNotify = new HashMap<String, Boolean>();
            
            //assume none of the jobs ran
            for (String job : alertThresholdMap.keySet())
            {
                jobNotify.put(job, Boolean.TRUE);
            }

            //get the all the relevant trigger events
            //earliest date we care about
            long maxThresholdMillis = Collections.max(alertThresholdMap.values()).longValue() * 60 * 60 * 1000; // hours -> milliseconds
            Date minDate = new Date(System.currentTimeMillis()-maxThresholdMillis);
            //latest date we care about
            Date maxDate = new Date (System.currentTimeMillis());
            //the trigger event types we care about
            TriggerEvent.TRIGGER_EVENT_TYPE[] triggerEventTypes = new TriggerEvent.TRIGGER_EVENT_TYPE[]{TriggerEvent.TRIGGER_EVENT_TYPE.COMPLETE};

            List<TriggerEvent> events = triggerEventManager.getTriggerEvents(minDate, maxDate, new ArrayList<String>(alertThresholdMap.keySet()), null, triggerEventTypes);

            //Iterate over the events and figure out if they've completed within the expected threshold
            Iterator it = events.iterator();
            while (it.hasNext())
            {
                    TriggerEvent event = (TriggerEvent) it.next();

                    String jobName=event.getJobName();

                    Date date = event.getTime();
                    //time elapsed since the job completed:
                    long millisPassed = System.currentTimeMillis()-date.getTime();
                    //time that millisPassed must be less than:
                    Long threshold = alertThresholdMap.get(jobName).longValue() * 60 * 60 * 1000;  // hours -> milliseconds
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
            it = alertThresholdMap.keySet().iterator();
            while (it.hasNext())
            {
                    String jobName = (String) it.next();
                    //add jobName if we need to notify jobName
                    if (jobNotify.get(jobName))
                    {
                            jobsToEmail.add(jobName + " (" + alertThresholdMap.get(jobName) + ")");
                    }
            }

            //send the email (if 1+ jobs didn't run)
            if (!jobsToEmail.isEmpty())
            {
                // OWLTODO: Refactor this email attempt/fallback code into its own reusable method
                Map<String, String> replacementValues = new HashMap<String, String>();
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
                    List<String> toAddresses = new ArrayList<String>();
                    toAddresses.add(developerHelperService.getUserRefFromUserEid("bbailla2"));
                    toAddresses.add(developerHelperService.getUserRefFromUserEid("bjones86"));
                    toAddresses.add(developerHelperService.getUserRefFromUserEid("plukasew"));
                    toAddresses.add(developerHelperService.getUserRefFromUserEid("sfoster9"));

                    emailTemplateService.sendRenderedMessages(EMAIL_HEARTBEAT_TEMPLATE,toAddresses,replacementValues,EMAIL_NO_REPLY_ADDRESS, "Sakai Owl quartz job checker");
                }
            }
                
        } // end heartMonitor()
        
        /**
	 * Load and register a one or more email templates (contained in the given
	 * .xml file) with the email template service
	 * 
	 * @author bjones86
	 * @param fileName - the name of the .xml file to load
	 * @param templateKey - the key (name) of the template to be saved to the service
	 */
	@SuppressWarnings( "deprecation" )
	private void loadTemplate( String fileName, String templateKey )
	{
		// Get the current session and current user
		Session currentSession = SessionManager.getCurrentSession();
		org.sakaiproject.user.api.User currentUser = org.sakaiproject.user.cover.UserDirectoryService.getCurrentUser();
		
		try
		{
			// Change the session so we have admin rights
			currentSession.setUserId( ADMIN_ID );
			
			// Load up the resource as an input stream
			InputStream input = CheckQuartzJobs.class.getClassLoader().getResourceAsStream( fileName );
			if( input == null )
				log.error(LOG_PREFIX + "Could not load resource from '" + fileName + "'. Skipping..." );
			else
			{
				// Parse the XML, get all the child templates
				Document document = new SAXBuilder().build( input );
				List<?> childTemplates = document.getRootElement().getChildren( "emailTemplate" );
				Iterator<?> iter = childTemplates.iterator();
				
				// Create and register a template with the service for each one found in the XML file
				while( iter.hasNext() )
					xmlToTemplate( (Element) iter.next(), templateKey );
			}
		}
		catch( JDOMException e ) { log.error(LOG_PREFIX + e.getMessage(), e ); }
		catch( IOException e ) 	 { log.error(LOG_PREFIX + e.getMessage(), e ); }
		
		// Demote the session back to the original user
		finally { currentSession.setUserId( currentUser.getId() ); }
	}
	
	/**
	 * Extracts the email template fields from the given XML element. Checks
	 * if the email template already exists; if it does and the new copy has
	 * a higher version number than that of the one currently in the service, 
	 * it will update the existing email template. Otherwise it will just save 
	 * the template to the service.
	 * 
	 * @author bjones86
	 * @param xmlTemplate - the XML element containing the email template data
	 * @param templateKey - the key (name) of the template to be saved to the service
	 */
	private void xmlToTemplate( Element xmlTemplate, String templateKey )
	{
		// Extract the necessary data out of the XML element
		String subject 		= xmlTemplate.getChildText( TMPLT_ELMNT_SUBJECT );
		String body 		= xmlTemplate.getChildText( TMPLT_ELMNT_MESSAGE );
		String bodyHtml 	= xmlTemplate.getChildText( TMPLT_ELMNT_HTML );
		String locale 		= xmlTemplate.getChildText( TMPLT_ELMNT_LOCALE );
		String strVersion 	= xmlTemplate.getChildText( TMPLT_ELMNT_VERSION );
		String decodedHtml 	= bodyHtml;
		
		// Check if there is an html message supplied...
		try { decodedHtml = URLDecoder.decode( bodyHtml, "utf8" ); }
		catch( UnsupportedEncodingException e ) { log.error(LOG_PREFIX + e.getMessage(), e ); decodedHtml = null; }
		catch( NullPointerException e )			{ log.error(LOG_PREFIX + e.getMessage(), e ); decodedHtml = null; }
		
		// Check if there was a version supplied...
		Integer iVersion = Integer.valueOf( 1 );
		try { iVersion = Integer.valueOf( strVersion ); }
		catch( NumberFormatException e ) { log.error(LOG_PREFIX + e.getMessage(), e ); iVersion = Integer.valueOf( 1 ); }
		catch( NullPointerException e )  { log.error(LOG_PREFIX + e.getMessage(), e ); iVersion = Integer.valueOf( 1 ); }
		
		// If the template already exists, don't do anything (just return)
		if( emailTemplateService.getEmailTemplate( templateKey, new Locale( locale ) ) != null )
			return;
		
		// Populate the template with the data
		EmailTemplate template = new EmailTemplate();
		template.setSubject( subject );
		template.setMessage( body );
		template.setLocale( locale );
		template.setKey( templateKey );
		template.setOwner( ADMIN_ID );
		template.setLastModified( new Date() );
		template.setVersion( iVersion );
		if( decodedHtml != null )
			template.setHtmlMessage( decodedHtml );
		
		// Save the template and log a success message
		emailTemplateService.saveTemplate( template );
		log.info(LOG_PREFIX + "Added '" + templateKey + "' to the email template service" );
	}
        
} // end class
