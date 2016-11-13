package sample;

import gnu.io.CommPortIdentifier;
import sample.util.DumpUtil;
import serial.io.SerialCommunicator;
import serial.io.SerialIOCallbacks;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Connect a GPS with its USB cable.
 * Serial port (/dev/ttyUSB0 below) may vary.
 */
public class GPSReader implements SerialIOCallbacks
{
  @Override
  public void connected(boolean b)
  {
    System.out.println("GPS connected: " + b);
  }

  private int lenToRead = 0;
  private int bufferIdx = 0;
  private byte[] serialBuffer = new byte[256];

  @Override
  public void onSerialData(byte b)
  {
//  System.out.println("\t\tReceived character [0x" + Integer.toHexString(b) + "]");
    serialBuffer[bufferIdx++] = (byte)(b & 0xFF);
    if (b == 0xA) // \n 
    {
      // Message completed
      byte[] mess = new byte[bufferIdx];
      for (int i=0; i<bufferIdx; i++)
        mess[i] = serialBuffer[i];
      serialOutput(mess);
      // Reset
      lenToRead = 0;
      bufferIdx = 0;
    }
  }

  public void serialOutput(byte[] mess)
  {
    if (true) // verbose...
    {
      try
      {
        String[] sa = DumpUtil.dualDump(mess);
        if (sa != null)
        {
          System.out.println("\t>>> [From GPS] Received:");
          for (String s: sa)
            System.out.println("\t\t"+ s);                
        }
      }
      catch (Exception ex)
      {
        ex.printStackTrace();
      }
    }
  }

  public static void main(String[] args)
  {
    final GPSReader gpsReader = new GPSReader();
    final SerialCommunicator sc = new SerialCommunicator(gpsReader);
    sc.setVerbose(false);
    
    Map<String, CommPortIdentifier> pm = sc.getPortList();
    Set<String> ports = pm.keySet();
    if (ports.size() == 0) {
      System.out.println("No serial port found.");
      System.out.println("Did you run as administrator (sudo) ?");
    }
    System.out.println("== Serial Port List ==");
    for (String port : ports)
      System.out.println("-> " + port);
    System.out.println("======================");

    String serialPortName = System.getProperty("serial.port", "/dev/ttyUSB0");
    String baudRateStr = System.getProperty("baud.rate", "4800");
    System.out.println(String.format("Opening port %s:%s", serialPortName, baudRateStr));
    CommPortIdentifier serialPort = pm.get(serialPortName);
    if (serialPort == null)
    {
      System.out.println(String.format("Port %s not found, aborting", serialPortName));
      System.exit(1);
    }
    final Thread thread = Thread.currentThread();

    Runtime.getRuntime().addShutdownHook(new Thread()
    {
      public void run()
      {
        try
        {
          synchronized (thread)
          {
            thread.notify();
          }
        }
        catch (Exception ex)
        {
          ex.printStackTrace();
        }
      }
    });
    try 
    {
      sc.connect(serialPort, "GPS", Integer.parseInt(baudRateStr));
      boolean b = sc.initIOStream();
      System.out.println("IO Streams " + (b?"":"NOT ") + "initialized");
      sc.initListener();

      synchronized(thread)
      {
        try
        {
          thread.wait();
          System.out.println("Notified.");
        }
        catch (InterruptedException ie)
        {
          ie.printStackTrace();
        }
      }
    }
    catch (Exception ex) 
    {
      ex.printStackTrace();
    }        

    try {  sc.disconnect(); } catch (IOException ioe) { ioe.printStackTrace(); }
    System.out.println("Done.");
  }
}
