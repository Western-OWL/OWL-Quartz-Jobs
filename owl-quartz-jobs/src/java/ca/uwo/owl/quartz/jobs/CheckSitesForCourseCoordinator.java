package ca.uwo.owl.quartz.jobs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.Getter;
import lombok.Setter;

import org.apache.log4j.Logger;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.authz.api.AuthzGroup;
import org.sakaiproject.authz.api.AuthzGroupService;
import org.sakaiproject.authz.api.AuthzPermissionException;
import org.sakaiproject.authz.api.GroupNotDefinedException;
import org.sakaiproject.authz.api.Member;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.site.api.SiteService.SelectionType;
import org.sakaiproject.site.api.SiteService.SortType;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;

/**
 * This Quartz job is responsible for scanning all existing course sites in Sakai.
 * It will then determine if the current site has both an 'Instructor' and a
 * 'Course Coordinator' in the roster (or any of the other 'maintain' roles defined
 * in sakai.properties). If yes, the job will remove the 'Instructor' role from the 
 * course coordinator (or other 'maintain' type) user for this site (maintain role). 
 * If no, it does nothing.
 * 
 * This project was derived and refactored from the quartz-example project provided
 * by Steve Swinsburg, which can be found here:
 * (https://source.sakaiproject.org/contrib/lancaster.ac.uk/quartz-example/)
 * 
 * @author Brian Jones (bjones86@uwo.ca)
 *
 */
public class CheckSitesForCourseCoordinator implements Job
{
	// Class memebers
	private static final Logger log 						= Logger.getLogger( CheckSitesForCourseCoordinator.class );
	private static final String SAKAI_PROPS_SAKORA_ROLES	= "sitemanage.siteCreation.maintainRoles.sakora";
	private static final String SAKAI_PROPS_PRIMARY_ROLE	= "sitemanage.siteCreation.maintainRoles.primaryRole";
	private static final String SAKAI_PROPS_PRIMARY_SAKORA	= "sitemanage.siteCreation.maintainRoles.sakora.primaryRole";
	
	// Instance members
	private List<String> 	sakoraRoles 	= new ArrayList<String>();
	private String			primaryRole		= "";
	private String			primarySakora	= "";
	
	// API's
	@Getter @Setter	private SiteService 			siteService;
	@Getter @Setter	private AuthzGroupService 		authzGroupService;
	@Getter @Setter private SessionManager			sessionManager;
	@Getter @Setter private CourseManagementService courseManagementService;
	
	/**
	 * init - perform any actions required here for when this bean starts up
	 */
	public void init()
	{
		if( log.isDebugEnabled() )
			log.debug( "init()" );
		
		// Get the list of 'sakora' maintain roles from sakai.properties
		try { sakoraRoles = Arrays.asList( ServerConfigurationService.getStrings( SAKAI_PROPS_SAKORA_ROLES ) ); }
		catch( Exception ex ) { log.warn( "Property not found: " + SAKAI_PROPS_SAKORA_ROLES ); sakoraRoles = new ArrayList<String>(); }
		
		// Get the 'primary roles' from sakai.properties
		primaryRole   = ServerConfigurationService.getString( SAKAI_PROPS_PRIMARY_ROLE );
		primarySakora = ServerConfigurationService.getString( SAKAI_PROPS_PRIMARY_SAKORA );
	}
	
	/**
	 * This is the method that is fired when the job is 'triggered'.
	 * 
	 * @param jobExecutionContext - the context of the job execution
	 * @throws JobExecutionException
	 */
	public void execute( JobExecutionContext jobExecutionContext ) throws JobExecutionException
	{
		if( log.isDebugEnabled() )
			log.debug( "execute()" );
		
		// Loop through a list of all course sites
		List<Site> sites = siteService.getSites( SelectionType.ANY, "course", null, null, SortType.NONE, null );
		for( Site site : sites )
		{
			// Get the realm ID of the site; get the sections for the site
			String realmId = siteService.siteReference( site.getId() );
			Set<String> sectionIds = authzGroupService.getProviderIds( realmId );
			Map<Member, String> memberRoleMap = new HashMap<Member, String>();
			
			// Loop through a list of members for this course site
			Set<Member> members = site.getMembers();
			for( Member member : members )
			{
				// Get the section-role map for this user
				String userEID = member.getUserEid();	
				Map<String, String> sectionRoles = courseManagementService.findSectionRoles( userEID );
				
				// Loop through the sections in the section-role map...
				for( String section : sectionRoles.keySet() )
					if( sectionIds.contains( section ) )								// If the current section is for the current site
						if( sakoraRoles.contains( sectionRoles.get( section ) ) )		// If the list of sakora roles contains this users sakora role
							memberRoleMap.put( member, sectionRoles.get( section ) );	// Add them to the member-sakoraRole map
			}
			
			// If there are more than one entry in the member-sakoraRole map...
			if( memberRoleMap.size() > 1 )
			{
				// Get all the members that have the course role of 'Instructor'
				Map<Member, String> membersWithInstructorCourseRole = new HashMap<Member, String>();
				for( Member member : memberRoleMap.keySet() )
					if( primaryRole.equalsIgnoreCase( member.getRole().getId() ) )
						membersWithInstructorCourseRole.put( member, memberRoleMap.get( member ) );
				
				// Determine if the REAL Instructor is there
				boolean realInstructorPresent = false;
				for( Member member : memberRoleMap.keySet() )
					if( primaryRole.equalsIgnoreCase( member.getRole().getId() ) && primarySakora.equalsIgnoreCase( memberRoleMap.get( member ) ) )
						realInstructorPresent = true;
				
				// If the real instructor is present AND there is more than one user with the 'Instructor' course role...
				if( realInstructorPresent && membersWithInstructorCourseRole.size() > 1 )
				{
					// Loop through the membersWithInstructorsCourseRole map
					for( Member member : membersWithInstructorCourseRole.keySet() )
					{
						// If this user is NOT marked as an Instructor in the Sakora data...
						if( !memberRoleMap.get( member ).equals( primarySakora ) )
						{
							try
							{
								// Set the current session's user id to the admin user (for the realm edit)
								Session currentSession = sessionManager.getCurrentSession();
								currentSession.setUserId( "admin" );
								
								// "Remove" the member from the site so that it exposes their true Sakora role (course coordinator, etc.)
								AuthzGroup realmEdit = authzGroupService.getAuthzGroup( realmId );
								realmEdit.removeMember( member.getUserId() );
								authzGroupService.save( realmEdit );
								currentSession.setUserId( null );
								
								// Log a success message
								log.info( "Successfully exposed Sakora role (user: " + member.getUserId() + ", site: " + realmId );
							}
							
							// Log any exceptions thrown during the realm edit
							catch( GroupNotDefinedException ex ) { log.error( "Realm does not exist: " + realmId ); log.error( ex ); }
							catch( AuthzPermissionException ex ) 
							{ 
								log.error( "Insufficient privileges to remove user (user: " + member.getUserId() + ", site: " + realmId ); 
								log.error( ex ); 
							}
						}
					}
				}
			}
		}
	}
	
	// Getters
	public List<String> getSakoraRoles()	{ return this.sakoraRoles; }
	public String		getPrimaryRole()	{ return this.primaryRole; }
	public String		getPrimarySakora()	{ return this.primarySakora; }

	// Setters
	public void setSakoraRoles	( List<String> 	sakoraRoles )		{ this.sakoraRoles 		= sakoraRoles; }
	public void setPrimaryRole	( String 		primaryRole )		{ this.primaryRole		= primaryRole; }
	public void setPrimarySakora( String 		primarySakora)		{ this.primarySakora	= primarySakora; }
}
