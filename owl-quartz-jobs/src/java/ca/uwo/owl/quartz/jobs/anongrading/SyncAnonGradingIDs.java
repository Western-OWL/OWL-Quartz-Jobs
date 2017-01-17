package ca.uwo.owl.quartz.jobs.anongrading;

//import ca.uwo.owl.quartz.jobs.EmailTemplateHelper;
//import java.io.IOException;
//
//import java.util.ArrayList;
//import java.util.Date;
//import java.util.HashMap;
//import java.util.HashSet;
//import java.util.Iterator;
//import java.util.List;
//import java.util.Map;
//import java.util.Set;
//
//import javax.mail.internet.AddressException;
//import javax.mail.internet.InternetAddress;
//
//import org.apache.log4j.Logger;
//
//import org.quartz.Job;
//import org.quartz.JobExecutionContext;
//import org.quartz.JobExecutionException;
//
//import org.sakaiproject.component.cover.ServerConfigurationService;
//import org.sakaiproject.service.gradebook.shared.GradebookService;
//import org.sakaiproject.service.gradebook.shared.owl.anongrading.OwlAnonGradingID;

/**
 * Reads in anonymous grading ids from PeopleSoft data and updates the database.
 * 
 * @author bbailla2
 * 
 * 2013.10.09: bbailla2 - OQJ-?? - Reads from a csv file from the registrar and updates the OWL_ANON_GRADING_ID table
 * 2017.01.17: bjones86 - OQJ-34 - port to Sakai 11, update to Spring 4 and Quartz 2.2
 *
 */
public class SyncAnonGradingIDs /*implements Job*/
{
//	private static final Logger LOG = Logger.getLogger(SyncAnonGradingIDs.class);
//
//	// Email templates
//	private static final String ERROR_EMAIL_TEMPLATE_KEY = "owlquartzjobs.anongrading.sync.error";
//	private static final String ERROR_EMAIL_TEMPLATE_XML_FILE = "anonGradingErrorEmailTemplate.xml";
//	private static final String DUPLICATE_EMAIL_TEMPLATE_KEY = "owlquartzjobs.anongrading.sync.duplicates";
//	private static final String DUPLICATE_EMAIL_TEMPLATE_XML_FILE = "anonGradingDuplicatesEmailTemplate.xml";
//
//	//Sakai property indicating who will recive notifications from this job
//	private static final String PROP_NOTIFICATION_LIST = "owlquartzjobs.anongrading.sync.emailNotificationList";
//
//	//Sakai property indicating whether or not to delete rows that are not found in the CSV (default is true)
//	private static final String PROP_DO_DELETIONS = "owlquartzjobs.anongrading.sync.doDeletions";
//
//	//from address
//	private static final String EMAIL_NO_REPLY_ADDRESS = "no-reply@uwo.ca";
//
//	//email recipients
//	private final List<InternetAddress> recipients = new ArrayList<>();
//
//	private GradebookService gradebookService;
//	public void setGradebookService(GradebookService gradebookService)
//	{
//		this.gradebookService = gradebookService;
//	}
//
//	public GradebookService getGradebookService()
//	{
//		return gradebookService;
//	}
//
//	public void init()
//	{
//		LOG.info("init()");
//		String[] notificationListArray = ServerConfigurationService.getStrings(PROP_NOTIFICATION_LIST);
//		if (notificationListArray != null)
//		{
//			for (int i = 0; i < notificationListArray.length; ++i)
//			{
//				String email = notificationListArray[i];
//				try
//				{
//					recipients.add(new InternetAddress(email));
//				}
//				catch (AddressException ex)
//				{
//					throw new RuntimeException("Invalid recipient: " + email, ex);
//				}
//			}
//		}
//
//		EmailTemplateHelper.loadTemplate(ERROR_EMAIL_TEMPLATE_XML_FILE, ERROR_EMAIL_TEMPLATE_KEY);
//		EmailTemplateHelper.loadTemplate(DUPLICATE_EMAIL_TEMPLATE_XML_FILE, DUPLICATE_EMAIL_TEMPLATE_KEY);
//	}
//
//	@Override
//	public void execute( JobExecutionContext jobExecutionContext ) throws JobExecutionException
//	{
//		LOG.info("execute()");
//	
//		long startTime = System.currentTimeMillis();
//
//		AnonGradingCSVHandler csvHandler = new AnonGradingCSVHandler();
//
//		// true when the anon-grades.csv has moved into the batch folder
//		boolean movedToProcessingDir = false;
//		// true when archiving the batch processing folder has been attempted
//		boolean archiveAttempted = false;
//		try
//		{
//			//move the files to the processing directory (use the csvHandler)
//			LOG.info("Moving csv to processing directory");
//			csvHandler.moveToProcessingDir();
//			movedToProcessingDir = true;
//
//			//parse the csv
//			List<AnonGradingCSVRow> csvRows = csvHandler.getAnonGradingCSVRows();
//
//			List<AnonGradingCSVRow> duplicates = detectDuplicates(csvRows);
//
//			if (!duplicates.isEmpty())
//			{
//				LOG.warn("Duplicates found as follows:");
//				for (AnonGradingCSVRow duplicate : duplicates)
//				{
//					LOG.info(duplicate.toString());
//				}
//				LOG.info("-end of duplicates-");
//				sendDuplicatesEmail(duplicates);
//			}
//
//			//remove all duplicates
//			csvRows.removeAll(duplicates);
//
//			/*
//				Get all the rows from the database with sectionEIDs that match the CSV.
//				For any matches rows with matching sectionEIDs and userEIDs, update the gradingIDs if they've changed.
//				Remove all matches from csvRows
//				Create the remainder of csvRows in the database
//			*/
//
//			//assemble a set of gradingIds from the csv
//			Set<String> csvSectionEIDs = getSectionEIDsFromCSVRows(csvRows);
//
//			List<OwlAnonGradingID> owlAnonGradingIDs = gradebookService.getAnonGradingIds();
//			// convert database rows to map<sectionEid, map<userEid, OwlAnonGradingID>>
//			Map<String, Map<String, OwlAnonGradingID>> dbSectionsToUsersToGrades = convertOwlAnonGradingIDsToMaps(owlAnonGradingIDs);
//
//			//Prepare a set of OwlAnonGradingIDs that need to be deleted. Start with the entire database table, and remove anything we find in the csv
//			Set<OwlAnonGradingID> toDelete = new HashSet<OwlAnonGradingID>(owlAnonGradingIDs);
//
//			//Prepare a set of OwlAnonGradingIDs that need to be updated. Start with nothing, and we'll populate them as we find discrepancies.
//			Set<OwlAnonGradingID> toUpdate = new HashSet<OwlAnonGradingID>();
//
//			//Prepare the list of new csv rows that need to be inserted into the database. Start with everything, and we'll remove all the matches we've found
//			List<AnonGradingCSVRow> newRows = new ArrayList<>();
//			newRows.addAll(csvRows);
//
//			/*
//				Iterate over the csv rows and find what needs to be updated. 
//				Remove entries from newRows as we discover that they do not need to be inserted into the db
//			*/
//			for (AnonGradingCSVRow csvRow : csvRows)
//			{
//				String csvSectionEid = csvRow.getSectionEid();
//				String csvUserEid = csvRow.getUserEid();
//				Integer csvGradingID = csvRow.getGradingID();
//
//				//find if the db has a row matching the csvRow's sectionEid and userEid
//				Map<String, OwlAnonGradingID> dbUserToGradingIDs = dbSectionsToUsersToGrades.get(csvSectionEid);
//				if (dbUserToGradingIDs != null)
//				{
//					OwlAnonGradingID dbGradingID = dbUserToGradingIDs.get(csvUserEid);
//					if (dbGradingID != null)
//					{
//						// the row exists in the csv, so we don't need to delete it
//						toDelete.remove(dbGradingID);
//
//						// the row exists in the db, so we don't need to insert it
//						newRows.remove(csvRow);
//
//						//if the gradingID doesn't match, it needs to be updated in the db
//						if (!dbGradingID.getAnonGradingID().equals(csvGradingID))
//						{
//							LOG.info("will update: " + csvRow.toString());
//							dbGradingID.setAnonGradingID(csvGradingID);
//							toUpdate.add(dbGradingID);
//						}
//					}
//				}
//			}
//
//			Boolean doDelete = ServerConfigurationService.getBoolean(PROP_DO_DELETIONS, Boolean.TRUE);
//			int numDeleted = 0;
//			if (doDelete)
//			{
//				LOG.info("deleting");
//				numDeleted = gradebookService.deleteAnonGradingIds(toDelete);
//			}
//
//			LOG.info("updating");
//			int numUpdated = gradebookService.updateAnonGradingIds(toUpdate);
//
//			Set<OwlAnonGradingID> toInsert = new HashSet<OwlAnonGradingID>();
//			// All the csvRows with unique (sectionEid, userEid) pairs need to be updated
//			for (AnonGradingCSVRow csvRow : newRows)
//			{
//				LOG.info("will insert: " + csvRow.toString());
//				OwlAnonGradingID current = new OwlAnonGradingID();
//				current.setSectionEid(csvRow.getSectionEid());
//				current.setUserEid(csvRow.getUserEid());
//				current.setAnonGradingID(csvRow.getGradingID());
//				toInsert.add(current);
//			}
//
//			LOG.info("inserting");
//			int numInserted = gradebookService.createAnonGradingIds(toInsert);
//
//			// archive the file (use the csvHandler)
//			LOG.info("archiving");
//			archiveAttempted = true;
//			csvHandler.archiveCSV(true);
//
//			long timeElapsed = System.currentTimeMillis() - startTime;
//
//			LOG.info("Success. Deleted " + numDeleted + " entries, updated " + numUpdated + " entries, inserted " + numInserted + " entries. Took " + timeElapsed + " milliseconds.");
//		}
//		catch(IOException | JobExecutionException exception)
//		{
//			LOG.error("Exception was thrown: " + exception.getMessage());
//
//			// Archive the file
//			// Do this only if the file is in the processing directory and we haven't already attempted to archive
//			if (movedToProcessingDir && !archiveAttempted)
//			{
//				LOG.info("attempting to archive...");
//				try
//				{
//					// pass in false as there was an error
//					csvHandler.archiveCSV(false);
//					LOG.info("archiving successful");
//				}
//				catch (Exception archiveException)
//				{
//					LOG.error("Archiving failed, exception is: " + archiveException.getMessage());
//				}
//			}
//
//			sendErrorEmail(exception);
//		}
//	}
//
//	/**
//	 * Lists all duplicates in a list of AnonGradingCSVRows
//	 */
//	private List<AnonGradingCSVRow> detectDuplicates(List<AnonGradingCSVRow> csvRows)
//	{
//		if (csvRows == null)
//		{
//			throw new IllegalArgumentException("csvRows cannot be null");
//		}
//
//		//our return value
//		final List<AnonGradingCSVRow> duplicates = new ArrayList<>();
//
//		/*
//			I was going to implement this with sets, but there's no way to return all the duplicates; 
//			when adding or using .contains, etc. there's no way to retrieve the object that already exists in the set
//		*/
//
//		//indices of duplicates that have already been added to the duplicates list
//		Set<Integer> skippableIndices = new HashSet<>();
//
//		for (int i = 0; i < csvRows.size() - 1; i++)
//		{
//			if (skippableIndices.contains(i))
//			{
//				//i and all its duplicates have already been identified
//				continue;
//			}
//
//			AnonGradingCSVRow base = csvRows.get(i);
//
//			//True if base is a duplicate (used so we only add it once)
//			boolean baseAdded = false;
//
//			for (int j = i+1; j < csvRows.size(); j++)
//			{
//				if (skippableIndices.contains(j))
//				{
//					//j has already been identified as a duplicate
//					continue;
//				}
//
//				AnonGradingCSVRow other = csvRows.get(j);
//				if (base.getSectionEid().equals(other.getSectionEid()) && base.getUserEid().equals(other.getUserEid()))
//				{
//					skippableIndices.add(j);
//					if (!baseAdded)
//					{
//						//base hasn't been added yet, so add it now
//						duplicates.add(base);
//						baseAdded = true;
//					}
//					duplicates.add(other);
//				}
//			}
//		}
//
//		return duplicates;
//	}
//
//	/**
//	 * Gets a set of all the sectionEIDs from a list of csvRows
//	 */
//	private Set<String> getSectionEIDsFromCSVRows(List<AnonGradingCSVRow> csvRows)
//	{
//		Set<String> sectionEIDs = new HashSet<>();
//		for (AnonGradingCSVRow csvRow : csvRows)
//		{
//			sectionEIDs.add(csvRow.getSectionEid());
//		}
//		return sectionEIDs;
//	}
//
//	/**
//	 * Converts a list of OwlAnonGradingIDs to a map of section eids to a map of user eids to grading IDs.
//	 * This is useful if you need to look up the grading ID for a given section and student pair
//	 */
//	private Map<String, Map<String, OwlAnonGradingID>> convertOwlAnonGradingIDsToMaps (List<OwlAnonGradingID> gradingIDs)
//	{
//		Map<String, Map<String, OwlAnonGradingID>> converted = new HashMap<String, Map<String, OwlAnonGradingID>>();
//		for (OwlAnonGradingID current : gradingIDs)
//		{
//			String currentSectionEid = current.getSectionEid();
//			String currentUserEid = current.getUserEid();
//			Map<String, OwlAnonGradingID> userToGradingID = converted.get(currentSectionEid);
//			OwlAnonGradingID convertedGradingID = null;
//			if (userToGradingID == null)
//			{
//				//create it
//				userToGradingID = new HashMap<String, OwlAnonGradingID>();
//				converted.put(currentSectionEid, userToGradingID);
//			}
//			else
//			{
//				convertedGradingID = userToGradingID.get(currentUserEid);
//			}
//
//			if (convertedGradingID == null)
//			{
//				//add it to userToGrade
//				userToGradingID.put(currentUserEid, current);
//			}
//		}
//
//		return converted;
//	}
//
//	/**
//	 * Sends an email with the given exception's message to the recipients specified in sakai.properties
//	 */
//	private void sendErrorEmail(Exception exception) throws JobExecutionException
//	{
//		Map<String, String> replacementValues = new HashMap<>();
//		replacementValues.put("error", exception.getMessage());
//		replacementValues.put("node", ServerConfigurationService.getServerId());
//
//		try
//		{
//			EmailTemplateHelper.sendMail(ERROR_EMAIL_TEMPLATE_KEY, replacementValues, new InternetAddress(EMAIL_NO_REPLY_ADDRESS), recipients.toArray(new InternetAddress[0]));
//		}
//		catch (Exception emailException)
//		{
//			throw new JobExecutionException("An error occurred while sending email", emailException, false);
//		}
//	}
//
//	/**
//	 * Sends an email with the given duplicates to the recipients specified in sakai.properties. If duplicates is large, it directs the recipients to the logs.
//	 */
//	private void sendDuplicatesEmail(List<AnonGradingCSVRow> duplicates) throws JobExecutionException
//	{
//		final int threshold = 10;
//		String key = "duplicates";
//		Map<String, String> replacementValues = new HashMap<>();
//		if (duplicates.size() > threshold)
//		{
//			replacementValues.put(key, "Please see the logs for more details; current time: " + new Date().toString());
//		}
//		else
//		{
//			String strDuplicates = "";
//			Iterator<AnonGradingCSVRow> itDuplicates = duplicates.iterator();
//			while (itDuplicates.hasNext())
//			{
//				AnonGradingCSVRow current = itDuplicates.next();
//				strDuplicates = strDuplicates + current.toString();
//				if (itDuplicates.hasNext())
//				{
//					strDuplicates = strDuplicates + "\n";
//				}
//			}
//			replacementValues.put(key, strDuplicates); 
//		}
//		try
//		{
//			EmailTemplateHelper.sendMail(DUPLICATE_EMAIL_TEMPLATE_KEY, replacementValues, new InternetAddress(EMAIL_NO_REPLY_ADDRESS), recipients.toArray(new InternetAddress[0]));
//		}
//		catch (Exception emailException)
//		{
//			throw new JobExecutionException("An error occured while sending email", emailException, false);
//		}
//	}
} // end class
