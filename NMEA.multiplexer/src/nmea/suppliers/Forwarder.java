package nmea.suppliers;

public interface Forwarder {
	void write(byte[] mess); // Receives data

	void close();

	Object getBean();
}