package nmea.forwarders.displays;

import nmea.parser.Angle360;
import nmea.parser.Angle180;
import nmea.parser.Distance;
import nmea.parser.Temperature;
import nmea.parser.Pressure;
import nmea.parser.Depth;
import nmea.parser.Current;
import nmea.parser.GeoPos;
import nmea.parser.SolarDate;
import nmea.parser.Speed;
import nmea.parser.UTCDate;
import nmea.utils.NMEAUtils;

import java.io.File;
import java.io.FileReader;
import java.text.DecimalFormat;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;

import org.fusesource.jansi.AnsiConsole;
import context.NMEADataCache;
import util.GeomUtil;

public class CharacterModeConsole {
	private final static boolean DEBUG = "true".equals(System.getProperty("cc.verbose", "false"));

	private static int cellSize = 13;
	private static int dataSize = 5;
	private static int keySize = 3;
	private static int suffixSize = 3;

	private final static Format DF_22 = new DecimalFormat("#0.00");
	private final static Format DF_31 = new DecimalFormat("#00.0");
	private final static Format DF_3 = new DecimalFormat("##0");
	private final static Format DF_4 = new DecimalFormat("###0");
	private final static SimpleDateFormat SDF = new SimpleDateFormat("dd MMM yyyy HH:mm:ss 'UTC'");
	private final static SimpleDateFormat SOLAR_DATE_FORMAT = new SimpleDateFormat("dd MMM yyyy HH:mm:ss 'Solar'");

	static {
		SOLAR_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("etc/UTC"));
	}

	private Date loggingStarted = null;
	private boolean startedUpdatedWithRMC = false;
	private final SuperBool first = new SuperBool(true);

	private static Map<String, AssociatedData> suffixes = new HashMap<String, AssociatedData>();

	static {
		suffixes.put("BSP", new AssociatedData("kt", DF_22));
		suffixes.put("HDG", new AssociatedData("t", DF_3));
		suffixes.put("AWS", new AssociatedData("kt", DF_22));
		suffixes.put("SOG", new AssociatedData("kt", DF_22));
		suffixes.put("TWS", new AssociatedData("kt", DF_22));
		suffixes.put("CSP", new AssociatedData("kt", DF_22)); // Current Speed
		suffixes.put("AWA", new AssociatedData("", DF_3));
		suffixes.put("TWA", new AssociatedData("", DF_3));
		suffixes.put("HDG", new AssociatedData("t", DF_3));
		suffixes.put("COG", new AssociatedData("t", DF_3));
		suffixes.put("CDR", new AssociatedData("t", DF_3)); // Current Direction
		suffixes.put("TWD", new AssociatedData("t", DF_3));
		suffixes.put("MWT", new AssociatedData("C", DF_31)); // Water Temp
		suffixes.put("MTA", new AssociatedData("C", DF_31)); // Air Temp
		suffixes.put("MMB", new AssociatedData("mb", DF_4));  // Pressure at Sea Level
		suffixes.put("DBT", new AssociatedData("m", DF_31)); // Depth
		suffixes.put("LOG", new AssociatedData("nm", DF_4));  // Log
		suffixes.put("CCS", new AssociatedData("kt", DF_22)); // Current Speed
		suffixes.put("CCD", new AssociatedData("t", DF_3));  // Current Direction
		suffixes.put("TBF", new AssociatedData("m", DF_4));  // Time buffer (in minutes) for current calculation
		suffixes.put("XTE", new AssociatedData("nm", DF_22));
		suffixes.put("HUM", new AssociatedData("%", DF_31)); // Humidity
	}

	private static Map<String, Integer> nonNumericData = new HashMap<String, Integer>();

	static {
		nonNumericData.put("POS", 24);
		nonNumericData.put("GDT", 32); // GPS Date & Time
		nonNumericData.put("SLT", 32); // Solar Time
		nonNumericData.put("NWP", 10); // Next Way point
	}

	private static Map<String, String> colorMap = new HashMap<String, String>();

	static {
		colorMap.put("RED", EscapeSeq.ANSI_RED);
		colorMap.put("BLACK", EscapeSeq.ANSI_BLACK);
		colorMap.put("CYAN", EscapeSeq.ANSI_CYAN);
		colorMap.put("GREEN", EscapeSeq.ANSI_GREEN);
		colorMap.put("BLUE", EscapeSeq.ANSI_BLUE);
		colorMap.put("WHITE", EscapeSeq.ANSI_WHITE);
		colorMap.put("YELLOW", EscapeSeq.ANSI_YELLOW);
		colorMap.put("MAGENTA", EscapeSeq.ANSI_MAGENTA);
	}

	private Map<String, ConsoleData> consoleData = null;

	public CharacterModeConsole() {
		super();
		try {
//    System.setOut(new PrintStream(new FileOutputStream("out.txt", true)));
//    System.setErr(new PrintStream(new FileOutputStream("err.txt", true)));
			loggingStarted = new Date();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		// Start refresh thread, every 1 minutes by default
		if ("yes".equals(System.getProperty("console.refresh", "yes"))) {
			final int interval = 1;
			Thread refresher = new Thread("Char Console Reader") {
				public void run() {
					while (true) {
						try {
							Thread.sleep(interval * 60 * 1000L);
						} catch (Exception ex) {
						}
						first.setValue(true);
					}
				}
			};
			refresher.start();
		}
	}

	public void initializeConsole(Properties props) {
//  System.out.println("Displaying Character mode console");
		// Init console here
		AnsiConsole.systemInstall();
		AnsiConsole.out.println(EscapeSeq.ANSI_CLS);

		try {
			initConsole(props);
		} catch (Exception ex) {
			ex.printStackTrace();
		}

		try {
			Thread.sleep(1000L);
		} catch (Exception ex) {
		} // Not nice, I know...

		first.setValue(true);
		// The small touchscreen (320x240) in 8x8 resolution has 40 columns per line of text, in 6x12, 54 columns.
	}

	public void displayData(NMEADataCache ndc, Properties props) {
		if (first.isTrue()) {
			AnsiConsole.out.println(EscapeSeq.ANSI_CLS);
			first.setValue(false);
			try {
				initConsole(props);
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
		Set<String> keys = consoleData.keySet();
		for (String s : keys) {
			ConsoleData cd = consoleData.get(s);
			int col = cd.getX();
			int row = cd.getY();
			String value = "";

			synchronized (ndc) {
				if (nonNumericData.containsKey(s)) {
					if ("POS".equals(s)) {
						try {
							value = NMEAUtils.lpad(GeomUtil.decToSex(((GeoPos) ndc.get(NMEADataCache.POSITION, true)).lat, GeomUtil.NO_DEG, GeomUtil.NS), 12, " ") +
											NMEAUtils.lpad(GeomUtil.decToSex(((GeoPos) ndc.get(NMEADataCache.POSITION, true)).lng, GeomUtil.NO_DEG, GeomUtil.EW), 12, " ");
						} catch (Exception ex) {
							value = "-";
							//  ex.printStackTrace();
						}
					} else if ("GDT".equals(s)) {
						try {
							UTCDate utcDate = (UTCDate) ndc.get(NMEADataCache.GPS_DATE_TIME, true);
							value = NMEAUtils.lpad(SDF.format(utcDate.getValue()), 24, " ");
						} catch (Exception e) {
							value = "-";
							//  e.printStackTrace();
						}
					} else if ("SLT".equals(s)) {
						try {
							SolarDate solarDate = (SolarDate) ndc.get(NMEADataCache.GPS_SOLAR_TIME, true);
							value = NMEAUtils.lpad(SOLAR_DATE_FORMAT.format(solarDate.getValue()), 24, " ");
						} catch (Exception e) {
							value = "-";
							//   e.printStackTrace();
						}
					} else if ("NWP".equals(s)) {
						try {
							value = (String) ndc.get(NMEADataCache.TO_WP, true);
						} catch (Exception e) {
							value = "-";
							//   e.printStackTrace();
						}
					} else {
						try {
							value =
											NMEAUtils.lpad(suffixes.get(s).getFmt().format(getValueFromCache(s, ndc)), dataSize, " "); // + " ";
						} catch (Exception e) {
							value = "-";
							// e.printStackTrace();
						}
					}
				}
				String plot = plotOneValue(1 + ((col - 1) * cellSize), row + 1, value, colorMap.get(cd.getFgData()), colorMap.get(cd.getBgData()));
				AnsiConsole.out.println(plot);
//      try { Thread.sleep(10); } catch (Exception ex) {}
			}
		}
	}

	private double getValueFromCache(String key, NMEADataCache ndc) {
		double value = 0;
		if ("BSP".equals(key)) {
			try {
				value = ((Speed) ndc.get(NMEADataCache.BSP)).getValue();
			} catch (Exception ignore) {
			}
		} else if ("HDG".equals(key)) {
			try {
				value = ((int) ((Angle360) ndc.get(NMEADataCache.HDG_TRUE, true)).getDoubleValue()) % 360;
			} catch (Exception ex) {
			}
		} else if ("TWD".equals(key)) {
			try {
				value = ((int) ((Angle360) ndc.get(NMEADataCache.TWD, true)).getDoubleValue()) % 360;
			} catch (Exception ex) {
			}
		} else if ("AWS".equals(key)) {
			try {
				value = ((Speed) ndc.get(NMEADataCache.AWS, true)).getDoubleValue();
			} catch (Exception ex) {
			}
		} else if ("AWA".equals(key)) {
			try {
				value = (int) ((Angle180) ndc.get(NMEADataCache.AWA, true)).getDoubleValue();
			} catch (Exception ex) {
			}
		} else if ("TWS".equals(key)) {
			try {
				value = ((Speed) ndc.get(NMEADataCache.TWS, true)).getDoubleValue();
			} catch (Exception ex) {
			}
		} else if ("COG".equals(key)) {
			try {
				value = ((Angle360) ndc.get(NMEADataCache.COG)).getValue();
			} catch (Exception ex) {
			}
		} else if ("SOG".equals(key)) {
			try {
				value = ((Speed) ndc.get(NMEADataCache.SOG)).getValue();
			} catch (Exception ex) {
			}
		} else if ("TWA".equals(key)) {
			try {
				value = ((Angle180) ndc.get(NMEADataCache.TWA)).getValue();
			} catch (Exception ignore) {
			}
		} else if ("CDR".equals(key)) {
			try {
				value = ((Angle360) ndc.get(NMEADataCache.CDR)).getValue();
			} catch (Exception ignore) {
			}
		} else if ("CSP".equals(key)) {
			try {
				value = ((Speed) ndc.get(NMEADataCache.CSP)).getValue();
			} catch (Exception ignore) {
			}
		} else if ("LAT".equals(key)) {
			try {
				value = ((GeoPos) ndc.get(NMEADataCache.POSITION)).lat;
			} catch (Exception ignore) {
			}
		} else if ("LNG".equals(key)) {
			try {
				value = ((GeoPos) ndc.get(NMEADataCache.POSITION)).lng;
			} catch (Exception ignore) {
			}
		} else if ("LOG".equals(key)) {
			try {
				value = ((Distance) ndc.get(NMEADataCache.LOG)).getValue();
			} catch (Exception ignore) {
			}
		} else if ("MWT".equals(key)) {
			try {
				value = ((Temperature) ndc.get(NMEADataCache.WATER_TEMP)).getValue();
			} catch (Exception ignore) {
			}
		} else if ("MTA".equals(key)) {
			try {
				value = ((Temperature) ndc.get(NMEADataCache.AIR_TEMP)).getValue();
			} catch (Exception ignore) {
			}
		} else if ("MMB".equals(key)) {
			try {
				value = ((Pressure) ndc.get(NMEADataCache.BARO_PRESS)).getValue();
			} catch (Exception ignore) {
			}
		} else if ("HUM".equals(key)) {
			try {
				value = ((Double) ndc.get(NMEADataCache.RELATIVE_HUMIDITY)).doubleValue();
			} catch (Exception ignore) {
				value = 0d;
			}
		} else if ("DBT".equals(key)) {
			try {
				value = ((Depth) ndc.get(NMEADataCache.DBT)).getValue();
			} catch (Exception ignore) {
			}
		} else if ("CCS".equals(key)) {
			try {
				Current current = (Current) ndc.get(NMEADataCache.VDR_CURRENT);
				if (current == null) {
					Map<Long, NMEADataCache.CurrentDefinition> currentMap =
									((Map<Long, NMEADataCache.CurrentDefinition>) ndc.get(NMEADataCache.CALCULATED_CURRENT));  //.put(bufferLength, new NMEADataCache.CurrentDefinition(bufferLength, new Speed(speed), new Angle360(dir)));
					Set<Long> keys = currentMap.keySet();
					if (keys.size() != 1 && DEBUG)
						System.err.println("CCS: Nb entry(ies) in Calculated Current Map:" + keys.size());
					for (Long l : keys)
						value = currentMap.get(l).getSpeed().getValue();
				} else {
					value = current.speed;
				}
			} catch (Exception ignore) {
			}
		} else if ("CCD".equals(key)) {
			try {
				Current current = (Current) ndc.get(NMEADataCache.VDR_CURRENT);
				if (current == null) {
					Map<Long, NMEADataCache.CurrentDefinition> currentMap =
									((Map<Long, NMEADataCache.CurrentDefinition>) ndc.get(NMEADataCache.CALCULATED_CURRENT));  //.put(bufferLength, new NMEADataCache.CurrentDefinition(bufferLength, new Speed(speed), new Angle360(dir)));
					Set<Long> keys = currentMap.keySet();
					if (keys.size() != 1 && DEBUG)
						System.err.println("CCD: Nb entry(ies) in Calculated Current Map:" + keys.size());
					for (Long l : keys)
						value = currentMap.get(l).getDirection().getValue();
				} else {
					value = current.angle;
				}
			} catch (Exception ignore) {
			}
		} else if ("TBF".equals(key)) {
			try {
				Map<Long, NMEADataCache.CurrentDefinition> currentMap =
								((Map<Long, NMEADataCache.CurrentDefinition>) ndc.get(NMEADataCache.CALCULATED_CURRENT));  //.put(bufferLength, new NMEADataCache.CurrentDefinition(bufferLength, new Speed(speed), new Angle360(dir)));
				Set<Long> keys = currentMap.keySet();
				if (keys.size() != 1 && DEBUG)
					System.err.println("TBF: Nb entry(ies) in Calculated Current Map:" + keys.size());
				for (Long l : keys)
					value = l / (60 * 1000);
			} catch (Exception ignore) {
			}
		} else if ("XTE".equals(key)) {
			try {
				value = ((Distance) ndc.get(NMEADataCache.XTE)).getValue();
			} catch (Exception ignore) {
			}
		}
		return value;
	}

	private void initConsole(Properties consoleProps) throws Exception {
		consoleData = new HashMap<>();

		Enumeration<String> props = (Enumeration<String>) consoleProps.propertyNames();
		boolean lineZeroIsBusy = false;
		while (props.hasMoreElements()) {
			String prop = props.nextElement();
//    System.out.println("Prop:" + prop);
			if (!prop.equals("console.title")) {
				String value = consoleProps.getProperty(prop);
				String[] elem = value.split(",");
				int line = Integer.parseInt(elem[1].trim());
				if (line == 0)
					lineZeroIsBusy = true;
				consoleData.put(prop.trim(),
								new ConsoleData(prop.trim(),
												Integer.parseInt(elem[0].trim()),
												Integer.parseInt(elem[1].trim()),
												elem[2].trim(),
												elem[3].trim(),
												elem[4].trim(),
												elem[5].trim()));
			}
		}
		// First display
		AnsiConsole.out.println(EscapeSeq.ANSI_CLS);
		if (!lineZeroIsBusy) {
			String screenTitle = consoleProps.getProperty("console.title", " - Character-mode NMEA console -");
			AnsiConsole.out.println(EscapeSeq.ansiLocate(1, 1) + EscapeSeq.ansiSetTextAndBackgroundColor(EscapeSeq.ANSI_WHITE, EscapeSeq.ANSI_BLACK) + EscapeSeq.ANSI_BOLD + screenTitle + EscapeSeq.ANSI_NORMAL + EscapeSeq.ANSI_DEFAULT_BACKGROUND + EscapeSeq.ANSI_DEFAULT_TEXT);
		}
		// Ordered lists
		Map<Integer, Map<Integer, String>> table = new TreeMap<Integer, Map<Integer, String>>();
		Set<String> keys = consoleData.keySet();
		for (String s : keys) {
			ConsoleData cd = consoleData.get(s);
			int col = cd.getX();
			int row = cd.getY();
			Map<Integer, String> rowMap = table.get(row);
			if (rowMap == null) {
				rowMap = new TreeMap<Integer, String>();
				table.put(row, rowMap);
			}
			rowMap.put(col, cd.getKey());
		}

		Set<Integer> rows = table.keySet();
		for (Integer i : rows) {
			Map<Integer, String> cols = table.get(i);
			Set<Integer> values = cols.keySet();
			List<CharData> consoleLine = new ArrayList<CharData>();
			for (Integer j : values) {
				String k = cols.get(j);
//      System.err.print(k + " ");
				if (nonNumericData.containsKey(k)) {
//      if ("POS".equals(k))
					consoleLine.add(new CharData(k,
									NMEAUtils.lpad(" ", nonNumericData.get(k), " "), // "  ** **.**'N *** **.**'E",  //    "                        ",
									"", // suffixes.get(k).getSuffix(),
									cellSize,
									colorMap.get(consoleData.get(k).getFgData()),
									colorMap.get(consoleData.get(k).getBgData()),
									colorMap.get(consoleData.get(k).getFgTitle()),
									colorMap.get(consoleData.get(k).getBgTitle())));
				} else
					consoleLine.add(new CharData(k,
									0,
									dataSize,
									suffixes.get(k).getFmt(),
									suffixes.get(k).getSuffix(),
									cellSize,
									colorMap.get(consoleData.get(k).getFgData()),
									colorMap.get(consoleData.get(k).getBgData()),
									colorMap.get(consoleData.get(k).getFgTitle()),
									colorMap.get(consoleData.get(k).getBgTitle())));
			}
//    System.out.println();
			CharData[] cda = new CharData[consoleLine.size()];
			cda = consoleLine.toArray(cda);
			String dataLine = formatCharacterLine(1, 1 + i.intValue(), cda);
			AnsiConsole.out.println(dataLine);
//    System.out.println(dataLine);
		}
//  plotMessage("Console Ready...");
	}

	private static String formatCharacterLine(int x, int y, CharData[] lineData) {
		String line = "";
		line += EscapeSeq.ansiLocate(x, y);
		for (CharData cd : lineData) {
			//  int rpad = cd.getCellLen() - (cd.getKey().length() + cd.formattedValue().length() + cd.getSuffix().length() + 3);
			String s = EscapeSeq.ansiSetTextAndBackgroundColor(cd.getTitleColor(), cd.getTitleBackground()) +
							EscapeSeq.ANSI_BOLD +
							cd.getKey() +
							EscapeSeq.ansiSetTextAndBackgroundColor(cd.getValueColor(), cd.getValueBackground()) +
							EscapeSeq.ANSI_BOLD +
							" " + cd.formattedValue() + " " +
							EscapeSeq.ansiSetTextAndBackgroundColor(cd.getTitleColor(), cd.getTitleBackground()) +
							EscapeSeq.ANSI_BOLD +
							NMEAUtils.rpad(cd.getSuffix(), suffixSize, " ") +  // "(" + Integer.toString(rpad) + ")" +
							// DesktopUtilities.rpad("", " ", rpad) +
							EscapeSeq.ANSI_NORMAL +
							EscapeSeq.ANSI_DEFAULT_BACKGROUND + EscapeSeq.ANSI_DEFAULT_TEXT;
			line += s;
		}

		return line + "          ";
	}

	private static String plotOneValue(int x, int y, String value, String valueColor, String bgColor) {
		String line = "";
		line += EscapeSeq.ansiLocate(x + keySize, y);
		String s = EscapeSeq.ansiSetTextAndBackgroundColor(valueColor, bgColor) +
						EscapeSeq.ANSI_BOLD +
						" " + value + " ";
		line += s;
		return line;
	}

	private static void plotMessage(String mess) {
		String line = "";
		int x = 0, y = 10;
		line = EscapeSeq.ansiLocate(x, y) +
						EscapeSeq.ansiSetTextAndBackgroundColor(EscapeSeq.ANSI_WHITE, EscapeSeq.ANSI_BLUE) +
						EscapeSeq.ANSI_BOLD +
						mess +
						EscapeSeq.ANSI_NORMAL +
						EscapeSeq.ANSI_DEFAULT_BACKGROUND +
						EscapeSeq.ANSI_DEFAULT_TEXT;
		;

		AnsiConsole.out.println(line);
	}

	private static class AssociatedData {
		private String suffix;
		private Format fmt;

		public String getSuffix() {
			return suffix;
		}

		public Format getFmt() {
			return fmt;
		}

		public AssociatedData(String suffix, Format fmt) {
			this.suffix = suffix;
			this.fmt = fmt;
		}
	}

	private static class CharData {
		private String key = "XXX";
		private double value = 0d;
		private String charValue = "";
		private Format fmt = null;
		private String suffix = "";
		private int valueLen = 5;
		private int cellLen = 15;

		private String valueColor = EscapeSeq.ANSI_RED;
		private String valueBackground = EscapeSeq.ANSI_BLACK;
		private String titleColor = EscapeSeq.ANSI_RED;
		private String titleBackground = EscapeSeq.ANSI_BLACK;

		public CharData(String key, String value, String suffix, int cellLen,
		                String valueColor, String valueBackground, String titleColor, String titleBackground) {
			this.key = key;
			this.charValue = value;
			this.suffix = suffix;
			this.cellLen = cellLen;
			this.valueColor = valueColor;
			this.valueBackground = valueBackground;
			this.titleColor = titleColor;
			this.titleBackground = titleBackground;
		}

		public CharData(String key, double value, int valueLen, Format fmt, String suffix, int cellLen,
		                String valueColor, String valueBackground, String titleColor, String titleBackground) {
			this.key = key;
			this.value = value;
			this.valueLen = valueLen;
			this.fmt = fmt;
			this.suffix = suffix;
			this.cellLen = cellLen;
			this.valueColor = valueColor;
			this.valueBackground = valueBackground;
			this.titleColor = titleColor;
			this.titleBackground = titleBackground;
		}

		public String formattedValue() {
			String str = "";
			if (this.fmt != null)
				str = NMEAUtils.lpad(this.fmt.format(this.value), this.valueLen, " ");
			else
				str = this.charValue;
			return str;
		}

		public String getKey() {
			return key;
		}

		public double getValue() {
			return value;
		}

		public Format getFmt() {
			return fmt;
		}

		public String getSuffix() {
			return suffix;
		}

		public int getCellLen() {
			return cellLen;
		}

		public String getValueColor() {
			return valueColor;
		}

		public String getValueBackground() {
			return valueBackground;
		}

		public String getTitleColor() {
			return titleColor;
		}

		public String getTitleBackground() {
			return titleBackground;
		}
	}

	private static class SuperBool {
		private boolean b;

		public SuperBool(boolean b) {
			this.b = b;
		}

		public void setValue(boolean b) {
			this.b = b;
		}

		public boolean isTrue() {
			return this.b;
		}
	}

	private static class ConsoleData {
		private String key;
		private int x;
		private int y;
		private String fgData;
		private String bgData;
		private String fgTitle;

		public String getKey() {
			return key;
		}

		public int getX() {
			return x;
		}

		public int getY() {
			return y;
		}

		public String getFgData() {
			return fgData;
		}

		public String getBgData() {
			return bgData;
		}

		public String getFgTitle() {
			return fgTitle;
		}

		public String getBgTitle() {
			return bgTitle;
		}

		private String bgTitle;

		public ConsoleData(String key, int x, int y, String fgData, String bgData, String fgTitle, String bgTitle) {
			this.key = key;
			this.x = x;
			this.y = y;
			this.fgData = fgData;
			this.bgData = bgData;
			this.fgTitle = fgTitle;
			this.bgTitle = bgTitle;
		}
	}

	public static String readableTime(long elapsed, boolean small) {
		long amount = elapsed;
		String str = "";
		final long SECOND = 1000L;
		final long MINUTE = 60 * SECOND;
		final long HOUR = 60 * MINUTE;
		final long DAY = 24 * HOUR;
		final long WEEK = 7 * DAY;

		if (amount >= WEEK) {
			int week = (int) (amount / WEEK);
			str += (week + (small ? " w " : " week(s) "));
			amount -= (week * WEEK);
		}
		if (amount >= DAY || str.length() > 0) {
			int day = (int) (amount / DAY);
			str += (day + (small ? " d " : " day(s) "));
			amount -= (day * DAY);
		}
		if (amount >= HOUR || str.length() > 0) {
			int hour = (int) (amount / HOUR);
			str += (hour + (small ? " h " : " hour(s) "));
			amount -= (hour * HOUR);
		}
		if (amount >= MINUTE || str.length() > 0) {
			int minute = (int) (amount / MINUTE);
			str += (minute + (small ? " m " : " minute(s) "));
			amount -= (minute * MINUTE);
		}
//  if (amount > SECOND || str.length() > 0)
		{
			int second = (int) (amount / SECOND);
			str += (second + ((amount % 1000) != 0 ? "." + (amount % 1000) : "") + (small ? " s " : " second(s) "));
			amount -= (second * SECOND);
		}
		return str;
	}

	public void quit() {
		AnsiConsole.out.println(EscapeSeq.ANSI_CLS);
		AnsiConsole.systemUninstall();
	}

	// Properties test
	public static void main(String[] args) throws Exception {
		String propFileName = System.getProperty("console.definition", "char.console.properties"); // "D:\\_mywork\\dev-corner\\olivsoft\\OlivSoftDesktop\\char.console.properties"
		Map<String, ConsoleData> consoleData = new HashMap<String, ConsoleData>();
		Properties consoleProps = new Properties();
		consoleProps.load(new FileReader(new File(propFileName)));
		Enumeration<String> props = (Enumeration<String>) consoleProps.propertyNames();
		boolean lineZeroIsBusy = false;
		while (props.hasMoreElements()) {
			String prop = props.nextElement();
			if (!prop.equals("console.title")) {
				//    System.out.println("Prop:" + prop);
				String value = consoleProps.getProperty(prop);
				String[] elem = value.split(",");
				int line = Integer.parseInt(elem[1].trim());
				if (line == 0)
					lineZeroIsBusy = true;
				consoleData.put(prop.trim(),
								new ConsoleData(prop.trim(),
												Integer.parseInt(elem[0].trim()),
												Integer.parseInt(elem[1].trim()),
												elem[2].trim(),
												elem[3].trim(),
												elem[4].trim(),
												elem[5].trim()));
			}
		}
		// First display
		AnsiConsole.out.println(EscapeSeq.ANSI_CLS);
		if (!lineZeroIsBusy) {
			String screenTitle = consoleProps.getProperty("console.title", " - Character-mode NMEA console -");
			AnsiConsole.out.println(EscapeSeq.ansiLocate(1, 1) + EscapeSeq.ansiSetTextAndBackgroundColor(EscapeSeq.ANSI_WHITE, EscapeSeq.ANSI_BLACK) + EscapeSeq.ANSI_BOLD + screenTitle + EscapeSeq.ANSI_NORMAL + EscapeSeq.ANSI_DEFAULT_BACKGROUND + EscapeSeq.ANSI_DEFAULT_TEXT);
		}
		// Ordered lists
		Map<Integer, Map<Integer, String>> table = new TreeMap<Integer, Map<Integer, String>>();
		Set<String> keys = consoleData.keySet();
		for (String s : keys) {
			ConsoleData cd = consoleData.get(s);
			int col = cd.getX();
			int row = cd.getY();
			Map<Integer, String> rowMap = table.get(row);
			if (rowMap == null) {
				rowMap = new TreeMap<Integer, String>();
				table.put(row, rowMap);
			}
			rowMap.put(col, cd.getKey());
		}

		Set<Integer> rows = table.keySet();
		for (Integer i : rows) {
			Map<Integer, String> cols = table.get(i);
			Set<Integer> values = cols.keySet();
			List<CharData> consoleLine = new ArrayList<CharData>();
			for (Integer j : values) {
				String k = cols.get(j);
//      System.out.print(k + " ");
				if ("POS".equals(k))
					consoleLine.add(new CharData(k, // POS!
									"  ** **.**'N *** **.**'E",
									//    "                        ",
									suffixes.get(k).getSuffix(),
									cellSize,
									colorMap.get(consoleData.get(k).getFgData()),
									colorMap.get(consoleData.get(k).getBgData()),
									colorMap.get(consoleData.get(k).getFgTitle()),
									colorMap.get(consoleData.get(k).getBgTitle())));
				else
					consoleLine.add(new CharData(k,
									0,
									dataSize,
									suffixes.get(k).getFmt(),
									suffixes.get(k).getSuffix(),
									cellSize,
									colorMap.get(consoleData.get(k).getFgData()),
									colorMap.get(consoleData.get(k).getBgData()),
									colorMap.get(consoleData.get(k).getFgTitle()),
									colorMap.get(consoleData.get(k).getBgTitle())));
			}
//    System.out.println();
			CharData[] cda = new CharData[consoleLine.size()];
			cda = consoleLine.toArray(cda);
			String dataLine = formatCharacterLine(1, 1 + i.intValue(), cda);
			AnsiConsole.out.println(dataLine);
//    System.out.println(dataLine);
		}
		try {
			Thread.sleep(2000);
		} catch (Exception ex) {
		}

		// Subsequent displays
		int i = 0;
		while (i < 10) {
			keys = consoleData.keySet();
			for (String s : keys) {
				ConsoleData cd = consoleData.get(s);
				int col = cd.getX();
				int row = cd.getY();
				String value = "";
				if ("POS".equals(s))
					value = "  12 34.56'N 123 45.67'W";
				else
					value = NMEAUtils.lpad(suffixes.get(s).getFmt().format(Math.random() * 100), dataSize, " "); // + " ";
				String plot = plotOneValue(1 + ((col - 1) * cellSize), row + 1, value, colorMap.get(cd.getFgData()), colorMap.get(cd.getBgData()));
				AnsiConsole.out.println(plot);
				//    try { Thread.sleep(100); } catch (Exception ex) {}
			}
			try {
				Thread.sleep(1000);
			} catch (Exception ex) {
			}
			i++;
		}
	}
}
