package ca.uwo.owl.quartz.jobs.anongrading;

import au.com.bytecode.opencsv.CSVReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;

import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.component.cover.ComponentManager;

/**
 * This class is responsible for the reading of CSVs that contain new Anonymous Grading ID data
 * 
 * @author bbailla2
 * 
 * 2013.10.09: bbailla2 - OQJ-?? - reads from a csv file from the registrar and updates the OWL_ANON_GRADING_ID table
 * 2017.01.17: bjones86 - OQJ-34 - port to Sakai 11, update to Spring 4 and Quartz 2.2
 *
 */
@Slf4j
public class AnonGradingCSVHandler 
{
	//sakai property specifying the absolute directory of the anonymous grading csv's pickup location
	private static final String PROP_CSV_LOCATION = "anongrading.csv.location";	
	//sakai property specifying the file name (excluding the path) of the csv file
	private static final String PROP_CSV_FILENAME = "anongrading.csv.filename";
	//sakai property specifying the directory to move the csv into before processing
	private static final String PROP_PROCESSING_LOCATION = "anongrading.processing.location";
	//sakai property specifying the directory to archive the csv after processing
	private static final String PROP_ARCHIVE_LOCATION = "anongrading.archive.location";
	//sakai property specifying the minimum grading ID (job will bail if a grading ID falls below the minimum)
	private static final String PROP_MIN_GRADING_ID = "anongrading.minimum.gradingId";
	private static final int MIN_GRADING_ID_DEFAULT = 1000;
	//sakai property specifying the maximum grading ID (job will bail if a grading ID exceeds the maximum)
	private static final String PROP_MAX_GRADING_ID = "anongrading.maximum.gradingId";
	private static final int MAX_GRADING_ID_DEFAULT = 9999;
	// sakai property specifiy the minimum number of rows that must appear in the file for this job to process it
	private static final String PROP_MIN_ROW_COUNT = "anongrading.minimum.rowCount";
	private static final int MIN_ROW_COUNT_DEFAULT = 10;

	//the default csv location relative to the sakai home path
	private static final String DEFAULT_CSV_LOCATION = "anon-grades";
	//the default csv file name
	private static final String DEFAULT_CSV_FILENAME = "anon-grades.csv";

	//prefix for the batch folders
	private static final String BATCH_PREFIX = "anon-grades-batch-";
	//suffixes for the batch folders
	private static final String FINISHED_SUFFIX = "-finished";
	private static final String FAILED_SUFFIX = "-failed";

	//The actual directory of the processing files that this thread is currently using (subdirectory of the anongrading.processing.location)
	private String threadProcessingLocation = "";

	public ServerConfigurationService getServerConfigurationService()
	{
		return (ServerConfigurationService) ComponentManager.get("org.sakaiproject.component.api.ServerConfigurationService");
	}

	/**
	 * Gets the anonymous grading CSV's pickup location
	 */
	private String getCSVLocation()
	{
		return getServerConfigurationService().getString(PROP_CSV_LOCATION, getDefaultCSVLocation());
	}

	/**
	 * Gets the anonymous grading CSV's file name (excluding the path)
	 */
	private String getCSVFileName()
	{
		return getServerConfigurationService().getString(PROP_CSV_FILENAME, DEFAULT_CSV_FILENAME);
	}

	/**
	 * Gets the location to move the anonymous grading CSV to be processed
	 */
	private String getProcessingLocation()
	{
		return getServerConfigurationService().getString(PROP_PROCESSING_LOCATION, getCSVLocation());
	}

	/**
	 * Gets the location to archive the CSV after processing
	 */
	private String getArchiveLocation()
	{
		return getServerConfigurationService().getString(PROP_ARCHIVE_LOCATION, getCSVLocation());
	}

	/**
	 * Gets the minimum grading ID; It is assumed that the python script has validated the csv, so if any grading ID falls below this number, then there is a serious problem and we will reject the whole csv
	 */
	private int getMinimumGradingID()
	{
		return getServerConfigurationService().getInt(PROP_MIN_GRADING_ID, MIN_GRADING_ID_DEFAULT);
	}

	/**
	 * Gets the maximum grading ID: It is assumed that the python script has validated the csv, so if any grading ID is above this number, then there is a serious problem and we will reject the whole csv
	 */
	private int getMaximumGradingID()
	{
		return getServerConfigurationService().getInt(PROP_MAX_GRADING_ID, MAX_GRADING_ID_DEFAULT);
	}

	/**
	 * Returns the minimum number of rows that must appear in the csv in order for it to be considered valid for processing
	 */
	private int getMinRowThreshold()
	{
		return getServerConfigurationService().getInt(PROP_MIN_ROW_COUNT, MIN_ROW_COUNT_DEFAULT);
	}

	/**
	 * Parses the anon-grading csv file and stores the result in a List of AnonGradingCSVRows.
	 * @return a list of AnonGradingCSVRows representing the contents of the CSV file
	 * throws AnonGradingCSVParseException if there's an error
	 */
	public List<AnonGradingCSVRow> getAnonGradingCSVRows()
	{
		List<AnonGradingCSVRow> csvRows = new ArrayList<>();

		//Get the location of the csv file. Assume it has been placed in the processing directory
		String csvLocation = threadProcessingLocation + File.separator + getCSVFileName();
		//Note: File objects aren't opened or closed, just useful to check the existence
		File csvFile = new File(csvLocation);
		if (!csvFile.exists())
		{
			log.error("{} doesn't exist", csvLocation);
			throw new AnonGradingCSVParseException("File doesn't exist");
		}
		else
		{
			// Get the minimum and maximum grading IDs for validation to do later
			int minGradingID = getMinimumGradingID();
			int maxGradingID = getMaximumGradingID();
			int threshold = getMinRowThreshold(); // OQJ-13  --plukasew

			//this is somewhat based on sakora's CsvHandlerBase.setup(CsvSyncContext context) method
			BufferedReader br = null;
			CSVReader csvr = null;
			try
			{
				br = new BufferedReader(new FileReader(csvFile));
				csvr = new CSVReader(br);
				csvr.readNext();	// skip past the header
				String[] line = csvr.readNext();
				int lineNumber = 1;
				while (line != null)
				{
					if (line.length == 1 && StringUtils.isBlank(line[0]))
					{
						//skip empty lines (they appear as having one empty string cell)
						line = csvr.readNext();
						++lineNumber;
						continue;
					}

					if (line.length < 3)
					{
						throw new AnonGradingCSVParseException("CSV row is too short. Line #" + lineNumber);
					}
					String sectionEid = StringUtils.trimToEmpty(line[0]);
					String userEid = StringUtils.trimToEmpty(line[1]);
					Integer gradingId = null;
					try
					{
						gradingId = Integer.parseInt(StringUtils.trimToEmpty(line[2]));
						if (gradingId < minGradingID || gradingId > maxGradingID)
						{
							log.error("Grading ID out of range");
							throw new AnonGradingCSVParseException("Grading ID is not between the minimum (" + minGradingID + ") and the maximum (" + maxGradingID + "): " + line[2] + "; userEid: " + userEid + "; sectionEid: " + sectionEid);
						}
					}
					catch(NumberFormatException e)
					{
						log.error("nfe while parsing grading ID");
						throw new AnonGradingCSVParseException("Grading ID is not an integer: " + line[2] + "; userEid: " + userEid + "; sectionEid: " + sectionEid);
					}
					AnonGradingCSVRow row = new AnonGradingCSVRow(sectionEid, userEid, gradingId);
					csvRows.add(row);
					line = csvr.readNext();
					++lineNumber;
				}

				// OQJ-13  --plukasew
				if (lineNumber < threshold)
				{
					log.error("Read only {} lines in CSV, threshold is ", lineNumber, threshold);
					throw new AnonGradingCSVParseException("Minimum row threshold not met");
				}
			}
			catch (IOException e)
			{
				log.error("IOException while reading CSV");
				throw new AnonGradingCSVParseException("IOException while reading CSV:\n" + e.getMessage(), e);
			}
			finally
			{
				try
				{
					if (csvr != null)
					{
						//closes the underlying reader
						csvr.close();
					}
					else if (br != null)
					{
						br.close();
					}
				}
				catch (IOException e)
				{
					throw new AnonGradingCSVParseException("Error closing the CSVReader:\n" + e.getMessage(), e);
				}
			}
		}

		return csvRows;
	}

	/**
	 * Moves files from the CSV pickup location to the processing directory
	 * @throws java.io.IOException
	 */
	public void moveToProcessingDir() throws IOException
	{
		//Create the processing batch folder within the processing location. The batch folder will be marked with the current time, and threadProcessingLocation will keep track of this
		threadProcessingLocation = getProcessingLocation() + File.separator + BATCH_PREFIX + System.currentTimeMillis();
		File processingPath = new File(threadProcessingLocation);
		processingPath.mkdirs();

		//Move the files from the pickup location to the processing batch folder
		moveFiles(getCSVLocation(), threadProcessingLocation);
	}


	/**
	 * Archives the CSV file from the processing directory into the archiving directory
	 * @param success archive's title will include the word 'finished' if true; 'failed' if false
	 * @throws java.io.IOException
	 */
	public void archiveCSV(boolean success) throws IOException
	{
		//Get the processing directory
		String processingLocation = threadProcessingLocation;
		File processingDir = new File (processingLocation);

		//Ensure the archiving location exists
		String archiveLocation = getArchiveLocation();
		File archiveParent = new File(archiveLocation);
		if (!archiveParent.exists())
		{
			archiveParent.mkdirs();
		}

		//Create the archive subfolder (in memory, not physically yet)
		String suffix = success ? FINISHED_SUFFIX : FAILED_SUFFIX;
		String archiveDir = archiveLocation + File.separator + BATCH_PREFIX + System.currentTimeMillis() + suffix; 
		File archivePath = new File(archiveDir);
		//Move the precessing directory itself to the archive
		processingDir.renameTo(archivePath);
	}

	/**
	 * From Sakora's CsvSyncServiceImpl:
	 * Move files in one directory into another. Both dirs
	 * must already exist. Bails with an IOException on the first 
	 * failed file move. Performs no cleanup as the result of
	 * such failure. Skips directory.
	 *
	 * @param from
	 * @param to
	 * @throws IOException
	 */
	private void moveFiles(String from, String to) throws IOException
	{
		File fromDir = new File(from);
		if (!fromDir.exists())
		{
			throw new IllegalArgumentException("Source directory does not exist [" + from + "]");
		}
		if (!fromDir.isDirectory())
		{
			throw new IllegalArgumentException("Source directory is not a directory [" + fromDir + "]");
		}
		File toDir = new File(to);
		if (!toDir.exists())
		{
			throw new IllegalArgumentException("Target directory does not exist [" + toDir + "]");
		}
		if (!toDir.isDirectory())
		{
			throw new IllegalArgumentException("Target directory is not a directory [" + toDir + "]");
		}
		for (File file : fromDir.listFiles())
		{
			if (file.isDirectory())
			{
				continue;
			}
			File newFile = new File(toDir, file.getName());
			if (!file.renameTo(newFile))
			{
				throw new IOException("Unable to move [" + file + "] to [" + newFile + "]");
			}
		}
	}

	/**
	 * Default location is tomcatDirectory/sakai/anon-grading/anon-grading.csv
	 */
	private String getDefaultCSVLocation()
	{
		ServerConfigurationService serverConfigurationService = getServerConfigurationService();
		String homePath = serverConfigurationService.getSakaiHomePath();
		String filePath = homePath + File.separator + DEFAULT_CSV_LOCATION;
		return filePath;
	}
} // end class
