package ca.uwo.owl.quartz.jobs.anongrading;

import org.apache.log4j.Logger;

/**
 * This class represents a row in the anonymous grading data CSV file.
 *
 * Each student has a grading ID, and each term the grading ID changes.
 * This means that sections from previous terms can have old grading IDs.
 * The data in the csv maps pairs of (section, user) to grading Ids.
 * 
 * @author bbailla2
 * 
 * 2013.10.09: bbailla2 - OQJ-?? - Represents a row in the csv - keeps track of a SectionEID, a userEID and a grading ID
 * 2017.01.17: bjones86 - OQJ-34 - port to Sakai 11, update to Spring 4 and Quartz 2.2
 *
 */
public class AnonGradingCSVRow
{
	private static final Logger LOG = Logger.getLogger(AnonGradingCSVRow.class);

	private String sectionEid;
	private String userEid;
	private Integer gradingID;

	/**
	 * Creates an AnonGradingCSVRow with the specified sectionEid, userEid, and gradingID
	 * @param sectionEid the eid of a section
	 * @param userEid the eid of a student
	 * @param gradingID the grading id of the student for the specified section
	 */
	public AnonGradingCSVRow(String sectionEid, String userEid, Integer gradingID)
	{
		this.sectionEid = sectionEid;
		this.userEid = userEid;
		this.gradingID = gradingID;
	}

	public String getSectionEid()
	{
		return sectionEid;
	}

	public void setSectionEid(String sectionEid)
	{
		this.sectionEid = sectionEid;
	}

	public String getUserEid()
	{
		return userEid;
	}

	public void setUserEid(String userEid)
	{
		this.userEid = userEid;
	}

	public Integer getGradingID()
	{
		return gradingID;
	}

	public void setGradingID(Integer gradingID)
	{
		this.gradingID = gradingID;
	}

	@Override
	public String toString()
	{
		StringBuilder sb = new StringBuilder("(");
		sb.append(sectionEid).append(", ").append(userEid).append(", ").append(gradingID).append(")");
		return sb.toString();
	}

	@Override
	public boolean equals(Object obj)
	{
		if (obj instanceof AnonGradingCSVRow)
		{
			AnonGradingCSVRow other = (AnonGradingCSVRow) obj;
			return nullAgnosticEquals(sectionEid, other.getSectionEid()) && nullAgnosticEquals(userEid, other.getUserEid()) && nullAgnosticEquals(gradingID, other.getGradingID());
		}
		return false;
	}

	@Override
	public int hashCode()
	{
		int hash = 3;
		hash = 19 * hash + (this.sectionEid != null ? this.sectionEid.hashCode() : 0);
		hash = 19 * hash + (this.userEid != null ? this.userEid.hashCode() : 0);
		hash = 19 * hash + (this.gradingID != null ? this.gradingID.hashCode() : 0);
		return hash;
	}

	/**
	 * Compares two objects with .equals(), but saves you the null check
	 */
	private boolean nullAgnosticEquals(Object obj1, Object obj2)
	{
		if (obj1 == null)
		{
			return obj2 == null;
		}
		return obj1.equals(obj2);
	}
} // end class
