package ca.uwo.owl.quartz.jobs;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import org.sakaiproject.authz.api.AuthzGroup;
import org.sakaiproject.authz.api.AuthzGroupService;
import org.sakaiproject.authz.api.AuthzPermissionException;
import org.sakaiproject.authz.api.GroupNotDefinedException;
import org.sakaiproject.authz.api.Member;
import org.sakaiproject.authz.api.SecurityAdvisor;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.coursemanagement.api.AcademicSession;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.coursemanagement.api.CourseOffering;
import org.sakaiproject.coursemanagement.api.Membership;
import org.sakaiproject.coursemanagement.api.Section;
import org.sakaiproject.coursemanagement.api.exception.IdNotFoundException;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.site.api.SiteService.SelectionType;
import org.sakaiproject.site.api.SiteService.SortType;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.user.api.UserNotDefinedException;

/**
 * This Quartz job is responsible for scanning all existing course sites in Sakai,
 * which contain at least one 'current/active' section.
 * It will then enforce all roster roles defined in sakai.properties. For example,
 * a user has the roster role of GA, and the site role of Instructor. Given that
 * there is at least one other user with the (maintain) role of Instructor,
 * the Enforcer will 'remove' the user to expose their true roster role of GA.
 * 
 * This project was derived and refactored from the quartz-example project provided
 * by Steve Swinsburg, which can be found here:
 * (https://source.sakaiproject.org/contrib/lancaster.ac.uk/quartz-example/)
 * 
 * @author Brian Jones (bjones86@uwo.ca)
 * 
 * 2014.06.24: bjones86 - OQJ-15 - add active academic session restriction
 * 2014.11.25: plukasew - 0QJ-18 - security improvement for realm edit
 * 2016.01.19: bjones86 - OQJ-28 - re-implement the role enforcer to be extendable to any/all Sakora roles
 * 2017.01.17: bjones86 - OQJ-34 - port to Sakai 11, update to Spring 4 and Quartz 2.2
 * 2017.08.23: bjones86 - OQJ-38 - add null check on Member object
 *
 */
@Slf4j
public class RosterRoleEnforcer implements Job
{
    // Class memebers
    private static final Map<String, String>    SITE_TO_SAKORA_ROLE_MAP = new HashMap<>();
    private static final Map<String, String>    SAKORA_TO_SITE_ROLE_MAP = new HashMap<>();
    private static final SecurityAdvisor        YES_MAN                 = (String userId, String function, String reference) -> SecurityAdvisor.SecurityAdvice.ALLOWED;

    // Sakai.properties
    private static final String SAKAI_PROPS_SAKORA_ROLES    = "sitemanage.siteCreation.sakoraRoles";
    private static final String SAKAI_PROPS_SITE_ROLES      = "sitemanage.siteCreation.siteRoles";

    // API's
    @Getter @Setter private SiteService             siteService;
    @Getter @Setter private AuthzGroupService       authzGroupService;
    @Getter @Setter private SessionManager          sessionManager;
    @Getter @Setter private CourseManagementService courseManagementService;
    @Getter @Setter private SecurityService         securityService;
    @Getter @Setter private UserDirectoryService    userDirectoryService;

    /**
     * init - perform any actions required here for when this bean starts up
     */
    public void init()
    {
        log.debug( "init()" );

        List<String> sakoraRoles = Arrays.asList( ArrayUtils.nullToEmpty( ServerConfigurationService.getStrings( SAKAI_PROPS_SAKORA_ROLES ) ) );
        List<String> siteRoles = Arrays.asList( ArrayUtils.nullToEmpty( ServerConfigurationService.getStrings( SAKAI_PROPS_SITE_ROLES ) ) );
        if( !sakoraRoles.isEmpty() && !siteRoles.isEmpty() && sakoraRoles.size() == siteRoles.size() )
        {
            for( int i = 0; i < siteRoles.size(); i++ )
            {
                SITE_TO_SAKORA_ROLE_MAP.put( siteRoles.get( i ), sakoraRoles.get( i ) );
                SAKORA_TO_SITE_ROLE_MAP.put( sakoraRoles.get( i ), siteRoles.get( i ) );
            }
        }
        else
        {
            log.warn( "Sakai.properties sitemanage.siteCreation.sakoraRoles and/or sitemanage.siteCreation.siteRoles non-existant or not equal." );
        }
    }

    /**
     * This is the method that is fired when the job is 'triggered'.
     * 
     * @param jobExecutionContext - the context of the job execution
     * @throws JobExecutionException
     */
    @Override
    public void execute( JobExecutionContext jobExecutionContext ) throws JobExecutionException
    {
        log.debug( "execute()" );

        // Short circuit if the map is not populated
        if( !SITE_TO_SAKORA_ROLE_MAP.isEmpty() )
        {
            // Loop through a list of all course sites
            List<Site> sites = siteService.getSites( SelectionType.ANY, "course", null, null, SortType.NONE, null );
            for( Site site : sites )
            {
                // Get the realm ID of the site; get the sections for the site
                String realmID = siteService.siteReference( site.getId() );
                Set<String> sectionIDs = authzGroupService.getProviderIds( realmID );

                // OQJ-15 - continue with this site only if it has at least one 'active' section
                if( !hasCurrentSection( sectionIDs ) )
                {
                    continue;
                }

                // Determine the 'maintain' role for the site
                String siteMaintainRole = site.getMaintainRole();
                String sakoraMaintainRole = SITE_TO_SAKORA_ROLE_MAP.get( siteMaintainRole );

                // Determine if there are Sakora enrolments for an official maintainer (instructor in the Sakora data)
                Map<String, Set<Membership>> sectionMembershipMap = new HashMap<>();
                boolean rostersHaveInstructor = false;
                for( String sectionID : sectionIDs )
                {
                    Set<Membership> memberships = courseManagementService.getSectionMemberships( sectionID );
                    if( !rostersHaveInstructor )
                    {
                        for( Membership membership : memberships )
                        {
                            if( sakoraMaintainRole.equals( membership.getRole() ) )
                            {
                                rostersHaveInstructor = true;
                                break;
                            }
                        }
                    }

                    sectionMembershipMap.put( sectionID, memberships );
                }

                // Target instructors with differing sakora/site roles
                findMembersToEnforce( sectionMembershipMap, sakoraMaintainRole, siteMaintainRole, realmID, true, rostersHaveInstructor, site );

                // Target all other roles now that instructors have been processed
                findMembersToEnforce( sectionMembershipMap, sakoraMaintainRole, siteMaintainRole, realmID, false, rostersHaveInstructor, site );
            }
        }
        else
        {
            log.warn( "Aborting without processing anything; roleMap is empty" );
        }
    }

    /**
     * This algorithm determines if a user's Sakora role differs from their site role.
     * If so, it will make a call to 'remove' the user from the realm, in effect exposing
     * their true Sakora role in the site.
     * 
     * @param sectionMembershipMap a map where the keys are section IDs belonging to the site, and the value is a Set of memberships for the section
     * @param sakoraMaintainRole the Sakora maintainer role for the site
     * @param siteMaintainRole the site maintainer role for this site
     * @param realmID the realm ID of the site
     * @param searchForMaintainers switch to determine if the algorithm is looking for maintainers, or all other roles
     * @param rostersHaveInstructor true if any of the rosters in the site have an officially defined instructor in the Sakora enrollments
     * @param site the site the sections and memberships belong to
     */
    public void findMembersToEnforce( Map<String, Set<Membership>> sectionMembershipMap, String sakoraMaintainRole, String siteMaintainRole, String realmID,
                                        boolean searchForMaintainers, boolean rostersHaveInstructor, Site site )
    {
        // Loop through the sections in the section->membership map
        for( String sectionID : sectionMembershipMap.keySet() )
        {
            // Loop through the memberships for this section
            for( Membership membership : sectionMembershipMap.get( sectionID ) )
            {
                // Skip to next user if we don't have a mapping for the user's Sakora role (not included in the properties)
                String membersSakoraRole = membership.getRole();
                if( !SAKORA_TO_SITE_ROLE_MAP.containsKey( membersSakoraRole ) )
                {
                    continue;
                }

                // If we're NOT searching for Sakora maintainers AND the user's sakora roel is NOT the sakora maintainer role
                // OR we ARE searching for Sakora maintainers AND the user's sakora role IS the sakora maintainer role...
                if( (!searchForMaintainers && !sakoraMaintainRole.equals( membersSakoraRole )) ||
                    (searchForMaintainers && sakoraMaintainRole.equals( membersSakoraRole )) )
                {
                    // Get the user's internal ID
                    String userID = "";
                    try { userID = userDirectoryService.getUserId( membership.getUserId() ); }
                    catch( UserNotDefinedException ex ) { log.error( "Can't find user by ID: {}", membership.getUserId(), ex ); }

                    // If the user ID couldn't be found for whatever reason, continue to the next Membership in the list
                    if( StringUtils.isEmpty( userID ) )
                    {
                        continue;
                    }

                    // Get the site member's site role, and determine what the user's site role SHOULD be based on their Sakora role
                    Member member = site.getMember( userID );

                    if( member != null )
                    {
                        String membersSiteRole = member.getRole().getId();
                        String membersSiteRoleShouldMatch = SAKORA_TO_SITE_ROLE_MAP.get( membersSakoraRole );

                        // If the user's Sakora role differes from their site role...
                        if( !membersSiteRole.equals( membersSiteRoleShouldMatch ) )
                        {
                            // If we're NOT searching for Sakora maintainers AND the user's site role is the maintain role
                            // AND all rosters attached to the site do not have an official maintainer defined in the Sakora data...
                            if( !searchForMaintainers && siteMaintainRole.equals( membersSiteRole ) && !rostersHaveInstructor )
                            {
                                // Log a message indicating why this enforcement was skipped (could leave no maintainer in the site)
                                log.info( "Skipping Sakora role enforcement (user: {}, site: {}, sakoraRole: {}, siteRole: {}), "
                                        + "as this enforcement could leave the site with no active maintainers.",
                                          new Object[] {membership.getUserId(), realmID, membersSakoraRole, member.getRole().getId()} );
                            }

                            // Otherwise, remove them to expose their true sakora/site role
                            else
                            {
                                removeUserFromRealm( member, membership, realmID, membersSakoraRole, membersSiteRoleShouldMatch );
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Remove the given user from the given realm.
     * This is used as a method of exposing a user's Sakora role when they have been
     * granted a differing role at the site level (whether intentional or not).
     * 
     * @param member the user to be removed
     * @param membership the membership enrollment of the user to be removed
     * @param realmID the realm to remove the user from
     * @param membersSakoraRole the role defined by Sakora for the user in the realm
     * @param membersEnforcedSiteRole the site role that corresponds to the Sakora role defined for the user in the realm
     */
    private void removeUserFromRealm( Member member, Membership membership, String realmID, String membersSakoraRole, String membersEnforcedSiteRole )
    {
        try
        {
            // "Remove" the user from the site so that it exposes their true Sakora role
            securityService.pushAdvisor( YES_MAN );
            AuthzGroup realmEdit = authzGroupService.getAuthzGroup( realmID );
            realmEdit.removeMember( member.getUserId() );
            authzGroupService.save( realmEdit );
            log.info( "Successfully enforced Sakora role (user: {}, site: {}, sakoraRole: {}, origSiteRole: {}, enforcedSiteRole: {})",
                      new Object[] {membership.getUserId(), realmID, membersSakoraRole, member.getRole().getId(), membersEnforcedSiteRole} );
        }
        catch( GroupNotDefinedException ex ) { log.error( "Realm does not exist: {}", realmID, ex ); }
        catch( AuthzPermissionException ex ) { log.error( "Insufficient privileges to remove user (user: {}, site: {}}", new Object[] {member.getUserId(), realmID}, ex ); }
        finally { securityService.popAdvisor( YES_MAN ); }
    }

    /**
     * Determines if any of the given section IDs are of a currently active term
     * 
     * @param sectionIDs - the set of section IDs to check
     * @return true if any of the given section IDs are of a currently active term; false otherwise
     */
    private boolean hasCurrentSection( Set<String> sectionIDs )
    {
        // If no section IDs are provided, return false
        if( CollectionUtils.isEmpty( sectionIDs ) )
        {
            return false;
        }

        // Get all the 'active' academic sessions; if there are none, return false
        List<AcademicSession> currentSessions = courseManagementService.getCurrentAcademicSessions();
        if( CollectionUtils.isEmpty( currentSessions ) )
        {
            return false;
        }

        // Loop through the section IDs provided
        for( String sectionID : sectionIDs )
        {
            // Get the section
            Section section = null;
            try { section = courseManagementService.getSection( sectionID ); }
            catch( IdNotFoundException ex ) { log.error( "Section does not exist, ID = <{}>. ", sectionID, ex ); }

            // If the section is not null, get the course offering; otherwise skip to next iteration (section ID)
            if( section != null )
            {
                CourseOffering offering = null;
                String courseOfferingID = section.getCourseOfferingEid();
                try { offering = courseManagementService.getCourseOffering( courseOfferingID ); }
                catch( IdNotFoundException ex ) { log.error( "CourseOffering does not exist, ID = <{}>.", courseOfferingID, ex ); }

                // If the course offering is not null, get the academic session; otherwise skip to next iteration (section ID)
                if( offering != null )
                {
                    // If the academic session is not null, and if the EID of the session matches that of any of the 'active' sessions, return true
                    AcademicSession session = offering.getAcademicSession();
                    if( session != null && currentSessions.stream().anyMatch( as -> as.getEid().equals( session.getEid() ) ) )
                    {
                        return true;
                    }
                }
            }
        }

        // None of the sections are of any of the currently active sessions; return false
        return false;
    }
}
