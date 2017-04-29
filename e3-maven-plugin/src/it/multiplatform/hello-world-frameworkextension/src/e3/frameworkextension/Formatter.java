package e3.frameworkextension;

import java.util.logging.LogRecord;

public class Formatter extends java.util.logging.Formatter {

	@Override
	public String format(LogRecord record) {
		System.out.println("custom formatter called");
		return super.formatMessage(record);
	}

}
