// bbailla2
// Reads from a csv file from the registrar and updates the OWL_ANON_GRADING_ID table
// 2013.10.09, bbailla2, Created

package ca.uwo.owl.quartz.jobs.anongrading;

import au.com.bytecode.opencsv.CSVReader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.File;
import java.io.FileReader;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;

import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.component.cover.ComponentManager;

/**
 * This class is responsible for the reading of CSVs that contain new Anonymous Grading ID data
 * @author bbailla2
 *
 */
public class AnonGradingCSVHandler 
{
	private static final Logger log = Logger.getLogger(AnonGradingCSVHandler.class);

	//sakai property specifying the absolute directory of the anonymous grading csv
	private static final String PROP_CSV_LOCATION = "anongrading.csv.location";
	
	//the default csv location relative to the sakai home path
	private static final String DEFAULT_CSV_LOCATION = "anon-grades" + File.separator + "anon-grades.csv";

	public ServerConfigurationService getServerConfigurationService()
	{
		return (ServerConfigurationService) ComponentManager.get("org.sakaiproject.component.api.ServerConfigurationService");
	}
	
	/**
	 * Parses the anon-grading csv file and stores the result in a List of AnonGradingCSVRows.
	 * @return a list of AnonGradingCSVRows representing the contents of the CSV file
	 * throws AnonGradingCSVParseException if there's an error
	 */
	public List<AnonGradingCSVRow> getAnonGradingCSVRows()
	{
		List<AnonGradingCSVRow> csvRows = new ArrayList<AnonGradingCSVRow>();

		//Get the location of the csv file. Use the sakai property if it exists, otherwise use the default location
		ServerConfigurationService serverConfigurationService = getServerConfigurationService();
		String csvLocation = serverConfigurationService.getString(PROP_CSV_LOCATION, getDefaultCSVLocation());

		//Note: File objects aren't opened or closed, just useful to check the existence
		File csvFile = new File(csvLocation);
		if (!csvFile.exists())
		{
			log.error(csvLocation + " doesn't exist");
			throw new AnonGradingCSVParseException("File doesn't exist");
		}
		else
		{
			//this is somewhat based on sakora's CsvHandlerBase.setup(CsvSyncContext context) method
			BufferedReader br = null;
			CSVReader csvr = null;
			try
			{
				br = new BufferedReader(new FileReader(csvFile));
				csvr = new CSVReader(br);
				String[] line = csvr.readNext();
				int lineNumber = 1;
				while (line != null)
				{
					if (line.length == 1 && "".equals(line[0]))
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
					String sectionEid = line[0];
					String userEid = line[1];
					Integer gradingId = null;
					try
					{
						gradingId = Integer.parseInt(line[2]);
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
				catch (Exception e)
				{
					throw new AnonGradingCSVParseException("Error closing the CSVReader:\n" + e.getMessage(), e);
				}
			}
		}
		
		return csvRows;
	}

	//OWLTODO: Implement a method to archive the file in its own folder after

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
