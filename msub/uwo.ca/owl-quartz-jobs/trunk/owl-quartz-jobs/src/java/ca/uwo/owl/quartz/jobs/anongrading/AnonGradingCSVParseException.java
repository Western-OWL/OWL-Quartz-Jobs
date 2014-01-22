package ca.uwo.owl.quartz.jobs.anongrading;

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

