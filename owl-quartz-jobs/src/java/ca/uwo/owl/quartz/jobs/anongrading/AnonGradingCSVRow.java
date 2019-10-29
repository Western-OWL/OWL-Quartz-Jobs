package ca.uwo.owl.quartz.jobs.anongrading;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class AnonGradingCSVRow
{
	@Getter @Setter private String sectionEid;
	@Getter @Setter private String userEid;
	@Getter @Setter private Integer gradingID;
}
