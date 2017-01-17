package ca.uwo.owl.quartz.jobs.anongrading;

/**
 * 
 * @author bbailla2
 * 
 * 2014.01.22: bbailla2 - OQJ-?? - ??
 * 2017.01.17: bjones86 - OQJ-34 - port to Sakai 11, update to Spring 4 and Quartz 2.2
 */
public class AnonGradingCSVParseException extends RuntimeException
{
	public AnonGradingCSVParseException(String message)
	{
		super(message);
	}

	public AnonGradingCSVParseException(String message, Exception cause)
	{
		super(message, cause);
	}
}
