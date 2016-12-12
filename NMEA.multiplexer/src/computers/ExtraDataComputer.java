package computers;

import context.ApplicationContext;
import context.NMEADataCache;
import nmea.api.Multiplexer;
import nmea.parser.Angle180;
import nmea.parser.Angle180EW;
import nmea.parser.Angle360;
import nmea.parser.ApparentWind;
import nmea.parser.OverGround;
import nmea.parser.RMC;
import nmea.parser.SolarDate;
import nmea.parser.Speed;
import nmea.parser.StringGenerator;
import nmea.parser.StringParsers;
import nmea.parser.TrueWindSpeed;
import nmea.parser.UTCDate;
import nmea.parser.UTCTime;
import nmea.parser.Wind;
import nmea.utils.NMEAUtils;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extra Data Computer. True Wind and Current.
 *
 * True Wind calculation requires:
 * <table border='1'>
 * <tr><td>GPS: COG & SOG, Declination</td><td>RMC, VTG, HDM</td></tr>
 * <tr><td>AWS, AWA</td><td>MWV, VWR</td></tr>
 * <tr><td>TRUE Heading</td><td>VHW, HDT, HDM</td></tr>
 * <tr><td>Leeway</td><td></td></tr>
 * </table>
 * Also take care of possible corrections:
 * <ul>
 * <li>BSP Coeff</li>
 * <li>AWS coeff, AWA offset</li>
 * <li>Heading Offset</li>
 * </ul>
 * <br>
 * See {@link ApplicationContext} and {@link NMEADataCache}
 */
public class ExtraDataComputer extends Computer {

	private final static String DEFAULT_PREFIX = "OS"; // OlivSoft

	private String generatedStringsPrefix = DEFAULT_PREFIX;
	private LongTimeCurrentCalculator longTimeCurrentCalculator = null;

	private final List<String> requiredStrings = Arrays.asList(new String[]{"RMC", "VHW", "VTG", "HDG", "HDM", "HDT", "MWV", "VWR"});

	public ExtraDataComputer(Multiplexer mux) {
	  this(mux, DEFAULT_PREFIX, LongTimeCurrentCalculator.DEFAULT_BUFFER_LENGTH);
	}

	public void setPrefix(String prefix) {
		this.generatedStringsPrefix = prefix;
	}

	public ExtraDataComputer(Multiplexer mux, String prefix, long tbl) {
		super(mux);
		if (prefix == null || prefix.length() != 2) {
			throw new RuntimeException("Prefix must exist, and be exactky 2 character long.");
		}
		this.generatedStringsPrefix = prefix;
		this.longTimeCurrentCalculator = new LongTimeCurrentCalculator(tbl);
		this.longTimeCurrentCalculator.setVerbose(this.verbose);
		this.longTimeCurrentCalculator.start();
	}

	public void setTimeBufferLength(long tbl) {
		if (verbose) {
			System.out.println(String.format("Settinh time buffer length to %d", tbl));
		}
		this.longTimeCurrentCalculator.setBufferLength(tbl);
	}
	/**
	 * Receives the data, and potentially produces new ones.
	 *
	 * @param mess
	 */
	@Override
	public void write(byte[] mess) {
		String sentence = new String(mess);
		if (StringParsers.validCheckSum(sentence)) {
			String sentenceID = StringParsers.getSentenceID(sentence);
			if (!generatedStringsPrefix.equals(StringParsers.getDeviceID(sentence)) && requiredStrings.contains(sentenceID)) { // The process
				if (this.verbose) {
					System.out.println(">>> TrueWind computer using " + sentence);
				}
				NMEADataCache cache = ApplicationContext.getInstance().getDataCache();
				switch (sentenceID) {
					case "RMC":
						RMC rmc = StringParsers.parseRMC(sentence);
						if (rmc != null) {
							Map<String, Object> rmcMap = new HashMap<>(5);
							rmcMap.put(NMEADataCache.SOG, new Speed(rmc.getSog()));
							rmcMap.put(NMEADataCache.POSITION, rmc.getGp());
							Date date = rmc.getRmcDate();
							if (date != null)
								rmcMap.put(NMEADataCache.GPS_DATE_TIME, new UTCDate(date));
							else
								rmcMap.put(NMEADataCache.GPS_DATE_TIME, null);

							Date time = rmc.getRmcTime();
							if (time != null) {
								rmcMap.put(NMEADataCache.GPS_TIME, new UTCTime(time));
							}
							rmcMap.put(NMEADataCache.COG, new Angle360(rmc.getCog()));
							rmcMap.put(NMEADataCache.DECLINATION, new Angle180EW(rmc.getDeclination()));

							// Compute Solar Time here
							try {
								if (rmc != null && (rmc.getRmcDate() != null || rmc.getRmcTime() != null) && rmc.getGp() != null) {
									long solarTime = -1L;
									if (rmc.getRmcDate() != null)
										solarTime = rmc.getRmcDate().getTime() + NMEAUtils.longitudeToTime(rmc.getGp().lng);
									else
										solarTime = rmc.getRmcTime().getTime() + NMEAUtils.longitudeToTime(rmc.getGp().lng);
									Date solarDate = new Date(solarTime);
									rmcMap.put(NMEADataCache.GPS_SOLAR_TIME, new SolarDate(solarDate));
								}
							} catch (Exception ex) {
								ex.printStackTrace();
							}
							cache.putAll(rmcMap);
						}
						break;
					case "VTG":
						OverGround overGround = StringParsers.parseVTG(sentence);
						if (overGround != null) {
							Map<String, Object> map = new HashMap<>(2);
							map.put(NMEADataCache.COG, new Angle360(overGround.getCourse()));
							map.put(NMEADataCache.SOG, new Speed(overGround.getSpeed()));
							cache.putAll(map);
						}
						break;
					case "VHW":
						double[] vhw = StringParsers.parseVHW(sentence);
						if (vhw == null)
							return;
						double bsp = vhw[StringParsers.BSP_in_VHW];
				//	double hdm = vhw[StringParsers.HDM_in_VHW];
						if (bsp != -Double.MAX_VALUE) {
							cache.put(NMEADataCache.BSP, new Speed(bsp));
						}
						break;
					case "HDG":
						double[] hdgs = StringParsers.parseHDG(sentence);
						int hdg = (int) hdgs[StringParsers.HDG_in_HDG];
						double dev = hdgs[StringParsers.DEV_in_HDG];
						double var = hdgs[StringParsers.VAR_in_HDG];
						if (dev == -Double.MAX_VALUE && var == -Double.MAX_VALUE) {
							cache.put(NMEADataCache.HDG_COMPASS, new Angle360(hdg));
						} else {
							double dec = 0d;
							if (dev != -Double.MAX_VALUE)
								dec = dev;
							else
								dec = var;
							cache.put(NMEADataCache.DECLINATION, new Angle180EW(dec));
							cache.put(NMEADataCache.HDG_COMPASS, new Angle360(hdg /* - dec */));
						}
						break;
					case "HDM":
						int hdm = StringParsers.parseHDM(sentence);
						cache.put(NMEADataCache.HDG_COMPASS, new Angle360(hdm));
						break;
					case "HDT":
						int hdt = StringParsers.parseHDT(sentence);
						cache.put(NMEADataCache.HDG_TRUE, new Angle360(hdt));
					case "MWV":
						Wind mwv = StringParsers.parseMWV(sentence);
						if (mwv != null && mwv instanceof ApparentWind) { // TrueWind not used for now
							Map<String, Object> map = new HashMap<>(2);
							map.put(NMEADataCache.AWS, new Speed(mwv.speed));
							int awa = mwv.angle;
							if (awa > 180)
								awa -= 360;
							map.put(NMEADataCache.AWA, new Angle180(awa));
							cache.putAll(map);
						}
						break;
					case "VWR":
						Wind vwr = StringParsers.parseVWR(sentence);
						if (vwr != null) {
							Map<String, Object> map = new HashMap<>(2);
							map.put(NMEADataCache.AWS, new Speed(vwr.speed));
							int awa = vwr.angle;
							if (awa > 180)
								awa -= 360;
							map.put(NMEADataCache.AWA, new Angle180(awa));
							cache.putAll(map);
						}
						break;
					default:
						break;
				}
				double twa = 0d;
				double tws = 0d;
				double twd = 0;
				double decl = 0d;
				double csp = 0d;
				int cdr = 0;
				synchronized (cache) {
					NMEAUtils.computeAndSendValuesToCache(cache);
					// True Wind
					try {
						twa = ((Angle180) cache.get(NMEADataCache.TWA)).getValue();
					} catch (NullPointerException ignore) {
					} catch (Exception ignore) {
						System.err.println("From " + this.getClass().getName() + ", getting TWA from the cache:" + ignore.toString());
					}
					try {
						tws = ((TrueWindSpeed) cache.get(NMEADataCache.TWS)).getValue();
					} catch (NullPointerException ignore) {
					} catch (Exception ignore) {
						System.err.println("From " + this.getClass().getName() + ", getting TWS from the cache:" + ignore.toString());
					}
					try {
						twd = ((Angle360) cache.get(NMEADataCache.TWD)).getValue();
					} catch (NullPointerException ignore) {
					} catch (Exception ignore) {
						System.err.println("From " + this.getClass().getName() + ", getting TWD from the cache:" + ignore.toString());
					}
					try {
						decl = ((Angle180EW) cache.get(NMEADataCache.DECLINATION)).getValue();
					} catch (NullPointerException ignore) {
					} catch (Exception ignore) {
						System.err.println("From " + this.getClass().getName() + ", getting Decl from the cache:" + ignore.toString());
					}

					try {
						long currentTimeBuffer = 0L;
						Map<Long, NMEADataCache.CurrentDefinition> currentMap =
										((Map<Long, NMEADataCache.CurrentDefinition>) cache.get(NMEADataCache.CALCULATED_CURRENT));
						Set<Long> keys = currentMap.keySet();
						//  if (keys.size() != 1)
						//    System.out.println("1 - Nb entry(ies) in Calculated Current Map:" + keys.size());
						for (Long l : keys) {
							int tbl = (int) (l / (60 * 1000));
							if (tbl > currentTimeBuffer) { // Take the bigger one.
								currentTimeBuffer = tbl;
								csp = currentMap.get(l).getSpeed().getValue();
								cdr = (int) Math.round(currentMap.get(l).getDirection().getValue());
							}
						}
					} catch (NullPointerException ignore) {
					} catch (Exception ignore) {
						System.err.println("From " + this.getClass().getName() + ", getting CALCULATED_CURRENT from the cache:" + ignore.toString());
					}
				}
				//  System.out.println("From TrueWindSentenceInsertion, TWS:" + tws);

				String nmeaVWT = StringGenerator.gerenateVWT(generatedStringsPrefix, tws, twa);
				String nmeaMWV = StringGenerator.generateMWV(generatedStringsPrefix, tws,
								(int) Math.round(twa),
								StringParsers.TRUE_WIND);
				String nmeaMWD = StringGenerator.generateMWD(generatedStringsPrefix, twd, tws, decl);

				this.produce(nmeaMWV);
				this.produce(nmeaVWT);
				this.produce(nmeaMWD);

				if (csp != 0 && !Double.isNaN(csp) && cdr != 0) {
					if (verbose) {
						System.out.println(String.format(">>>                                     Current Speed %f, dir %d", csp, cdr));
					}
					String nmeaVDR = StringGenerator.generateVDR(generatedStringsPrefix, csp, cdr, cdr - decl);
					this.produce(nmeaVDR);
				}
			}
		}
	}

	@Override
	public void setVerbose(boolean verbose) {
		super.setVerbose(verbose);
		this.longTimeCurrentCalculator.setVerbose(verbose);
	}

	@Override
	public void close() {
		System.out.println("- Stop Computing True Wind, " + this.getClass().getName());
		if (this.longTimeCurrentCalculator != null) {
			this.longTimeCurrentCalculator.stop();
		}
	}

	public static class ComputerBean {
		String cls;
		String type = "tw-current";
		long timeBufferLength = 600000;
		int cacheSize = 0;
		boolean verbose = false;
		String prefix = "OS";

		public int getCacheSize() {
			return this.cacheSize;
		}

		public long getTimeBufferLength() {
			return timeBufferLength;
		}

		public String getPrefix() {
			return prefix;
		}

		public boolean isVerbose() {
			return verbose;
		}

		public ComputerBean(ExtraDataComputer instance) {
			this.cls = instance.getClass().getName();
			this.cacheSize = ApplicationContext.getInstance().getDataCache().size();
			this.verbose = instance.isVerbose();
			this.timeBufferLength = instance.longTimeCurrentCalculator.getBufferLength();
			this.prefix = instance.generatedStringsPrefix;
		}
	}

	@Override
	public Object getBean() {
		return new ComputerBean(this);
	}
}