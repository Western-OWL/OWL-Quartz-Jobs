package ca.uwo.owl.quartz.jobs;

import java.io.InputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.mail.internet.InternetAddress;
import lombok.extern.slf4j.Slf4j;

import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;

import org.sakaiproject.authz.api.SecurityAdvisor;
import org.sakaiproject.authz.api.SecurityAdvisor.SecurityAdvice;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.email.api.EmailService;
import org.sakaiproject.emailtemplateservice.model.EmailTemplate;
import org.sakaiproject.emailtemplateservice.model.RenderedTemplate;
import org.sakaiproject.emailtemplateservice.service.EmailTemplateService;

/**
 * A class that helps handle common tasks with email templates
 * 
 * @author bbailla2, bjones86
 * 
 * 2017.01.17: bjones86 - OQJ-34 - port to Sakai 11, update to Spring 4 and Quartz 2.2
 **/
@Slf4j
public class EmailTemplateHelper
{
	private static final String UTF8_ENC_STRING = "utf8";
	private static final String TMPLT_EMAIL_TMPLT = "emailTemplate";

	// email template constants
	private static final String ADMIN_ID					= "admin"; 			// The login ID for the admin user
	private static final String TMPLT_ELMNT_SUBJECT			= "subject"; 		// The name of the subject element
	private static final String TMPLT_ELMNT_MESSAGE			= "message"; 		// The name of the message element
	private static final String TMPLT_ELMNT_HTML			= "messagehtml"; 	// The name of the message html element
	private static final String TMPLT_ELMNT_LOCALE			= "locale"; 		// The name of the locale element
	private static final String TMPLT_ELMNT_VERSION			= "version"; 		// The name of the version element

	private static SecurityService getSecurityService()
	{
		return (SecurityService) ComponentManager.get("org.sakaiproject.authz.api.SecurityService");
	}

	/**
	* Load and register a one or more email templates (contained in the given
	* .xml file) with the email template service
	* 
	* @author bjones86, bbailla2
	* 
	* @param fileName - the name of the .xml file to load
	* @param templateKey - the key (name) of the template to be saved to the service
	*/
	public static void loadTemplate( String fileName, String templateKey )
	{
		SecurityService securityService = getSecurityService();

		// Create the SecurityAdvisor (elevated permissions needed to use EmailTemplateService)
		SecurityAdvisor yesMan = (String userID, String function, String reference) -> SecurityAdvice.ALLOWED;

		try
		{
			// Push the yesMan SA on the stack and perform the necessary actions
			securityService.pushAdvisor( yesMan );

			// Load up the resource as an input stream
			InputStream input = EmailTemplateHelper.class.getClassLoader().getResourceAsStream( fileName );
			if( input == null )
			{
				log.error( "Could not load resource from '{}'. Skipping...", fileName );
			}
			else
			{
				// Parse the XML, get all the child templates
				Document document = new SAXBuilder().build( input );
				List<?> childTemplates = document.getRootElement().getChildren( TMPLT_EMAIL_TMPLT );
				Iterator<?> iter = childTemplates.iterator();

				// Create and register a template with the service for each one found in the XML file
				while( iter.hasNext() )
				{
					xmlToTemplate( (Element) iter.next(), templateKey );
				}
			}
		}
		catch( JDOMException | IOException e )
		{
			log.error( e.getMessage(), e );
		}

		// Pop the yesMan SA off the stack (remove elevated permissions)
		finally
		{
			securityService.popAdvisor( yesMan ); 
		}
	}

	/**
	* Extracts the email template fields from the given XML element. Checks
	* if the email template already exists; if it does and the new copy has
	* a higher version number than that of the one currently in the service, 
	* it will update the existing email template. Otherwise it will just save 
	* the template to the service.
	* 
	* @author bjones86, bbailla2
	* 
	* @param xmlTemplate - the XML element containing the email template data
	* @param templateKey - the key (name) of the template to be saved to the service
	*/
	private static void xmlToTemplate( Element xmlTemplate, String templateKey )
	{
		// Extract the necessary data out of the XML element
		String subject			= xmlTemplate.getChildText( TMPLT_ELMNT_SUBJECT );
		String body				= xmlTemplate.getChildText( TMPLT_ELMNT_MESSAGE );
		String bodyHtml			= xmlTemplate.getChildText( TMPLT_ELMNT_HTML );
		String locale			= xmlTemplate.getChildText( TMPLT_ELMNT_LOCALE );
		String strVersion		= xmlTemplate.getChildText( TMPLT_ELMNT_VERSION );
		String decodedHtml		= bodyHtml;

		// Check if there is an html message supplied...
		if( bodyHtml != null )
		{
			try 
			{
				decodedHtml = URLDecoder.decode( bodyHtml, UTF8_ENC_STRING ); 
			}
			catch( UnsupportedEncodingException | NullPointerException e )
			{
				log.error( e.getMessage(), e );
				decodedHtml = null;
			}
		}

		// Check if there was a version supplied...
		Integer iVersion;
		try
		{
			iVersion = Integer.valueOf( strVersion );
		}
		catch( NumberFormatException | NullPointerException e ) 
		{
			log.error( e.getMessage(), e );
			iVersion = 1;
		}

		// If the template already exists, don't do anything (just return)
		EmailTemplateService emailTemplateService = (EmailTemplateService) ComponentManager.get( EmailTemplateService.class );
		if( emailTemplateService.getEmailTemplate( templateKey, new Locale( locale ) ) != null )
		{
			return;
		}

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
		{
			template.setHtmlMessage( decodedHtml );
		}

		// Save the template and log a success message
		emailTemplateService.saveTemplate( template );
		log.info( "Added '{}' to the email template service", templateKey );
	}

	/**
	 * Sends an email message of the specified email template, replacement values, from the specified sender to the specified recipients
	 * @param emailTemplateKey the key of the email template to be used
	 * @param replacementValues the values to be replaced in the email template's subject and body
	 * @param sender the sender of the email
	 * @param recipients the recipients of the email
	 */
	public static void sendMail(String emailTemplateKey, Map replacementValues, InternetAddress sender, InternetAddress[] recipients)
	{
		EmailService emailService = (EmailService) ComponentManager.get("org.sakaiproject.email.api.EmailService");
		EmailTemplateService emailTemplateService = (EmailTemplateService) ComponentManager.get("org.sakaiproject.emailtemplateservice.service.EmailTemplateService");
		RenderedTemplate template = emailTemplateService.getRenderedTemplate(emailTemplateKey, Locale.ENGLISH, replacementValues);
		emailService.sendMail(sender, recipients, template.getRenderedSubject(), template.getRenderedMessage(), null, null, null);
	}
}
