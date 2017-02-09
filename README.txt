CheckQuartzJobs
	Background:
		-Exceptions happen, data gets bloated, performance degrades, jobs get stuck. This job monitors when other jobs have not completed successfully within a threshold of time
	Requirements:
		-Maps sakai.properties 'owlquartzjobs.checkquartzjobs.heartbeat.joblist' to 'owlquartzjobs.checkquartzjobs.heartbeat.jobthreshold' (OQJ-4)
		-Lists any jobs in the key set that haven't had a 'Completed' trigger event within their associated threshold (OQJ-2)
		-If this list is non-empty, it is sent via email to email addresses specified by owlquartzjobs.checkquartzjobs.email.notificationList (OQJ-2)
			-Email subject includes local sakai name (ie. sakdev / sakqat / owl) (OQJ-5)

Roster Role Enforcer
	Background:
		-A. There is a rule in Sakai that every site must have at least one member in its 'maintainer' role at all times. For course sites, the site maintainer role is generally instructor
		-B. The user who creates the site is put in the site maintainer role. So if a grade admin or course coordinator creates a course site, they will be stuck in the instructor role
		-C. Sometimes an instructor needs access before they are added to the site by the registrar, so a grade admin adds them to the site as a Secondary Instructor
	Requirements:
		-*SUPERSEDED* Scans course sites, if there exists a roster:INS with site:INS, we flip any roster:CCs from site:INS to site:CC (OQJ-1) *SUPERSEDED BY OQJ-28*
		-OQJ-28:
			-Map sakai properties sitemanage.siteCreation.sakoraRoles -> sitemanage.siteCreation.siteRoles (default: I->Instructor, CC->Course Coordinator, GA->Grade Admin)
			-For each course site
				-Collect Site Maintain Role, we'll call it 'INS'
				-Users whose rosterRole maps to INS and whose siteRole is not INS are 'removed' to expose their rosterRole
					-This handles C. Ie. manually added site:secondary instructors with roster:instructor become site:instructor. This also frees CCs & GAs who were stuck in the INS role because of A.
				-Users whose rosterRole does not map to INS will be removed to expose their rosterRole if their rosterRole:
					-is in sakoraRoles
					-differs from their site role
					-and an INS is present in the site
		-For performance, course sites scanned need only be from the currently active session (OQJ-15)





SyncAnonGradingIDs
	Background:
		-Faculty of Law can grade anonymously.
		-The registrar provides us with a CSV with columns: sectionID, userID, gradingID
		-We execute a python script to check its integrity, and sanitize the contents, then save the result in a pickup location for OWL
		-This job loads/syncs the sanitized CSV with the DB
	Requirements:
		-Loads anonymous grading IDs from a csv (OQJ-12)
		-Stores records in OWL_ANON_GRADING_ID table with columns: ID, SECTION_EID, USER_EID, ANON_GRADING_ID (OQJ-16)
			-DB records matching on section_eid, user_eid combos get updated
			-CSV records not found in the DB are inserted
			-DB records not found in the CSV are deleted
		-*NOT IMPLEMENTED* Guarantees uniqueness of section_eid, anon_grading_id combinations (OQJ-14) *NOT IMPLEMENTED* (handled by python script)
		-In case of failure, emails owlquartzjobs.anongrading.sync.emailNotificationList (OWJ-16)
		-Duplicates should be logged.  (OQJ-16)
			-If less than 10 dupliactes are detected, they are listed in email; otherwise the email should direct you to check the logs 
		-Sanity check: terminates if the CSV contains less than anongrading.minimum.rowCount rows; Default = 10 (OQJ-13)
		-All grading IDs must fall between 1000 and 9999; if any exceptions are encountered, an email should be sent and the job should be terminated (OQJ-20)
		-Performance of selects / updates / inserts must be decent (OQJ-17)
		-CSVs should be archived after each run (OQJ-32)
