package nmea.mux;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import nmea.api.NMEAReader;
import nmea.computers.Computer;
import nmea.computers.ExtraDataComputer;
import context.ApplicationContext;
import context.NMEADataCache;
import gnu.io.CommPortIdentifier;
import http.HTTPServer;
import http.HTTPServerInterface;
import http.RESTProcessorUtil;
import http.utils.DumpUtil;
import nmea.api.Multiplexer;
import nmea.api.NMEAClient;
import nmea.api.NMEAParser;
import nmea.consumers.client.BME280Client;
import nmea.consumers.client.DataFileClient;
import nmea.consumers.client.HTU21DFClient;
import nmea.consumers.client.RandomClient;
import nmea.consumers.client.SerialClient;
import nmea.consumers.client.TCPClient;
import nmea.consumers.client.WebSocketClient;
import nmea.consumers.reader.BME280Reader;
import nmea.consumers.reader.DataFileReader;
import nmea.consumers.reader.HTU21DFReader;
import nmea.consumers.reader.RandomReader;
import nmea.consumers.reader.SerialReader;
import nmea.consumers.reader.TCPReader;
import nmea.consumers.reader.WebSocketReader;
import nmea.forwarders.ConsoleWriter;
import nmea.forwarders.DataFileWriter;
import nmea.forwarders.Forwarder;
import nmea.forwarders.GPSdServer;
import nmea.forwarders.SerialWriter;
import nmea.forwarders.TCPServer;
import nmea.forwarders.WebSocketWriter;
import nmea.forwarders.rmi.RMIServer;
import nmea.forwarders.WebSocketProcessor;
import nmea.utils.NMEAUtils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;

public class GenericNMEAMultiplexer implements Multiplexer, HTTPServerInterface {
	private HTTPServer adminServer = null;

	private List<NMEAClient> nmeaDataClients = new ArrayList<>();
	private List<Forwarder> nmeaDataForwarders = new ArrayList<>();
	private List<Computer> nmeaDataComputers = new ArrayList<>();

	private static class Operation {
		String verb;
		String path;
		String description;
		Function<HTTPServer.Request, HTTPServer.Response> fn;

		public Operation(String verb, String path, Function<HTTPServer.Request, HTTPServer.Response> fn, String description) {
			this.verb = verb;
			this.path = path;
			this.description = description;
			this.fn = fn;
		}

		public String getVerb() {
			return verb;
		}

		public String getPath() {
			return path;
		}

		public String getDescription() {
			return description;
		}

		public Function<HTTPServer.Request, HTTPServer.Response> getFn() {
			return fn;
		}
	}

	/**
	 * Define all the REST operations to be managed
	 * by the HTTP server.
	 * <p>
	 * Frame path parameters with curly braces.
	 * <p>
	 * See {@link #processRequest(HTTPServer.Request, HTTPServer.Response)}
	 * See {@link HTTPServer}
	 */
	List<Operation> operations = Arrays.asList(
					new Operation(
									"GET",
									"/oplist",
									this::getOperationList,
									"List of all available operations."),
					new Operation(
									"GET",
									"/serial-ports",
									this::getSerialPorts,
									"Get the list of the available serial ports."),
					new Operation(
									"GET",
									"/channels",
									this::getChannels,
									"Get the list of the input channels"),
					new Operation(
									"GET",
									"/forwarders",
									this::getForwarders,
									"Get the list of the output channels"),
					new Operation(
									"GET",
									"/computers",
									this::getComputers,
									"Get the list of the computers"),
					new Operation(
									"DELETE",
									"/forwarders/{id}",
									this::deleteForwarder,
									"Delete an output channel"),
					new Operation(
									"DELETE",
									"/channels/{id}",
									this::deleteChannel,
									"Delete an input channel"),
					new Operation(
									"DELETE",
									"/computers/{id}",
									this::deleteComputer,
									"Delete a computer"),
					new Operation(
									"POST",
									"/forwarders",
									this::postForwarder,
									"Creates an output channel"),
					new Operation(
									"POST",
									"/channels",
									this::postChannel,
									"Creates an input channel"),
					new Operation(
									"POST",
									"/computers",
									this::postComputer,
									"Creates computer"),
					new Operation(
									"PUT",
									"/channels/{id}",
									this::putChannel,
									"Update channel"),
					new Operation(
									"PUT",
									"/forwarders/{id}",
									this::putForwarder,
									"Update forwarder"),
					new Operation(
									"PUT",
									"/computers/{id}",
									this::putComputer,
									"Update computer"),
					new Operation(
									"PUT",
									"/mux-verbose/{pos}",
									this::putMuxVerbose,
									"Update Multiplexer verbose"),
					new Operation(
									"GET",
									"/cache",
									this::getCache,
									"Get ALL the data in the cache"),
					new Operation(
									"DELETE",
									"/cache",
									this::resetCache,
									"Reset the cache"));

	public HTTPServer.Response processRequest(HTTPServer.Request request, HTTPServer.Response defaultResponse) {
		Optional<Operation> opOp = operations
						.stream()
						.filter(op -> op.getVerb().equals(request.getVerb()) && RESTProcessorUtil.pathMatches(op.getPath(), request.getPath()))
						.findFirst();
		if (opOp.isPresent()) {
			Operation op = opOp.get();
			request.setRequestPattern(op.getPath()); // To get the prms later on.
			HTTPServer.Response processed = op.getFn().apply(request); // Execute here.
			return processed;
		}
		return defaultResponse;
	}

	private HTTPServer.Response getSerialPorts(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);

		List<String> portList = getSerialPortList();
		Object[] portArray = portList.toArray(new Object[portList.size()]);
		String content = new Gson().toJson(portArray).toString();
		RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
		response.setPayload(content.getBytes());

		return response;
	}

	private HTTPServer.Response getChannels(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);

		List<Object> channelList = getInputChannelList();
		Object[] channelArray = channelList.stream()
						.collect(Collectors.toList())
						.toArray(new Object[channelList.size()]);

		String content = new Gson().toJson(channelArray);
		RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
		response.setPayload(content.getBytes());

		return response;
	}

	private HTTPServer.Response getForwarders(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		List<Object> forwarderList = getForwarderList();
		Object[] forwarderArray = forwarderList.stream()
						.collect(Collectors.toList())
						.toArray(new Object[forwarderList.size()]);

		String content = new Gson().toJson(forwarderArray);
		RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
		response.setPayload(content.getBytes());

		return response;
	}

	private HTTPServer.Response getComputers(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		List<Object> computerList = getComputerList();
		Object[] forwarderArray = computerList.stream()
						.collect(Collectors.toList())
						.toArray(new Object[computerList.size()]);

		String content = new Gson().toJson(forwarderArray);
		RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
		response.setPayload(content.getBytes());

		return response;
	}

	private HTTPServer.Response deleteForwarder(HTTPServer.Request request) {
		Optional<Forwarder> opFwd = null;
		Gson gson = null;
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), 204);
		List<String> prmValues = RESTProcessorUtil.getPrmValues(request.getRequestPattern(), request.getPath());
		if (prmValues.size() == 1) {
			String id = prmValues.get(0);
			switch (id) {
				case "console":
					opFwd = nmeaDataForwarders.stream()
									.filter(fwd -> fwd instanceof ConsoleWriter)
									.findFirst();
					response = removeForwarderIfPresent(request, opFwd);
					break;
				case "serial":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						SerialWriter.SerialBean serialBean = gson.fromJson(stringReader, SerialWriter.SerialBean.class);
						opFwd = nmeaDataForwarders.stream()
										.filter(fwd -> fwd instanceof SerialWriter &&
														((SerialWriter) fwd).getPort().equals(serialBean.getPort()))
										.findFirst();
						response = removeForwarderIfPresent(request, opFwd);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "file":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						DataFileWriter.DataFileBean dataFileBean = gson.fromJson(stringReader, DataFileWriter.DataFileBean.class);
						opFwd = nmeaDataForwarders.stream()
										.filter(fwd -> fwd instanceof DataFileWriter &&
														((DataFileWriter) fwd).getLog().equals(dataFileBean.getLog()))
										.findFirst();
						response = removeForwarderIfPresent(request, opFwd);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "tcp":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						TCPServer.TCPBean tcpBean = gson.fromJson(stringReader, TCPServer.TCPBean.class);
						opFwd = nmeaDataForwarders.stream()
										.filter(fwd -> fwd instanceof TCPServer &&
														((TCPServer) fwd).getTcpPort() == tcpBean.getPort())
										.findFirst();
						response = removeForwarderIfPresent(request, opFwd);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "gpsd":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						GPSdServer.GPSdBean gpsdBean = gson.fromJson(stringReader, GPSdServer.GPSdBean.class);
						opFwd = nmeaDataForwarders.stream()
										.filter(fwd -> fwd instanceof GPSdServer &&
														((GPSdServer) fwd).getTcpPort() == gpsdBean.getPort())
										.findFirst();
						response = removeForwarderIfPresent(request, opFwd);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "rmi":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						RMIServer.RMIBean rmiBean = gson.fromJson(stringReader, RMIServer.RMIBean.class);
						opFwd = nmeaDataForwarders.stream()
										.filter(fwd -> fwd instanceof RMIServer &&
														((RMIServer) fwd).getRegistryPort() == rmiBean.getPort())
										.findFirst();
						response = removeForwarderIfPresent(request, opFwd);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "ws":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						WebSocketWriter.WSBean wsBean = gson.fromJson(stringReader, WebSocketWriter.WSBean.class);
						opFwd = nmeaDataForwarders.stream()
										.filter(fwd -> fwd instanceof WebSocketWriter &&
														((WebSocketWriter) fwd).getWsUri().equals(wsBean.getWsUri()))
										.findFirst();
						response = removeForwarderIfPresent(request, opFwd);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "wsp":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						WebSocketProcessor.WSBean wsBean = gson.fromJson(stringReader, WebSocketProcessor.WSBean.class);
						opFwd = nmeaDataForwarders.stream()
										.filter(fwd -> fwd instanceof WebSocketProcessor &&
														((WebSocketProcessor) fwd).getWsUri().equals(wsBean.getWsUri()))
										.findFirst();
						response = removeForwarderIfPresent(request, opFwd);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "udp":
					response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED); 
					break;
				default:
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						Map<String, String> custom = (Map<String, String>)new Gson().fromJson(stringReader, Object.class);
						opFwd = nmeaDataForwarders.stream()
										.filter(fwd -> fwd.getClass().getName().equals(custom.get("cls")))
										.findFirst();
						response = removeForwarderIfPresent(request, opFwd);
					} else {
						response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED);
					}
					break;
			}
		} else {
			response.setStatus(HTTPServer.Response.BAD_REQUEST); 
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing path parameter");
		}
		return response;
	}

	private HTTPServer.Response deleteChannel(HTTPServer.Request request) {
		Optional<NMEAClient> opClient = null;
		Gson gson = null;
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NO_CONTENT);
		List<String> prmValues = RESTProcessorUtil.getPrmValues(request.getRequestPattern(), request.getPath());
		if (prmValues.size() == 1) {
			String id = prmValues.get(0);
			switch (id) {
				case "file":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						DataFileClient.DataFileBean dataFileBean = gson.fromJson(stringReader, DataFileClient.DataFileBean.class);
						opClient = nmeaDataClients.stream()
										.filter(channel -> channel instanceof DataFileClient &&
														((DataFileClient.DataFileBean) ((DataFileClient) channel).getBean()).getFile().equals(dataFileBean.getFile()))
										.findFirst();
						response = removeChannelIfPresent(request, opClient);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "serial":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						SerialClient.SerialBean serialBean = gson.fromJson(stringReader, SerialClient.SerialBean.class);
						opClient = nmeaDataClients.stream()
										.filter(channel -> channel instanceof SerialClient &&
														((SerialClient.SerialBean) ((SerialClient) channel).getBean()).getPort().equals(serialBean.getPort())) // No need for BaudRate
										.findFirst();
						response = removeChannelIfPresent(request, opClient);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "tcp":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						TCPClient.TCPBean tcpBean = gson.fromJson(stringReader, TCPClient.TCPBean.class);
						opClient = nmeaDataClients.stream()
										.filter(channel -> channel instanceof TCPClient &&
														((TCPClient.TCPBean) ((TCPClient) channel).getBean()).getPort() == tcpBean.getPort())
										.findFirst();
						response = removeChannelIfPresent(request, opClient);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "ws":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						WebSocketClient.WSBean wsBean = gson.fromJson(stringReader, WebSocketClient.WSBean.class);
						opClient = nmeaDataClients.stream()
										.filter(channel -> channel instanceof WebSocketClient &&
														((WebSocketClient.WSBean) ((WebSocketClient) channel).getBean()).getWsUri().equals(wsBean.getWsUri()))
										.findFirst();
						response = removeChannelIfPresent(request, opClient);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "bme280":
					opClient = nmeaDataClients.stream()
									.filter(channel -> channel instanceof BME280Client)
									.findFirst();
					response = removeChannelIfPresent(request, opClient);
					break;
				case "htu21df":
					opClient = nmeaDataClients.stream()
									.filter(channel -> channel instanceof HTU21DFClient)
									.findFirst();
					response = removeChannelIfPresent(request, opClient);
					break;
				case "rnd":
					opClient = nmeaDataClients.stream()
									.filter(channel -> channel instanceof RandomClient)
									.findFirst();
					response = removeChannelIfPresent(request, opClient);
					break;
				default:
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						Map<String, String> custom = (Map<String, String>)new Gson().fromJson(stringReader, Object.class);
						opClient = nmeaDataClients.stream()
										.filter(channel -> channel.getClass().getName().equals(custom.get("cls")))
										.findFirst();
						response = removeChannelIfPresent(request, opClient);
					} else {
						response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED);
					}
					break;
			}
		} else {
			response.setStatus(HTTPServer.Response.BAD_REQUEST); 
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing path parameter");
		}
		return response;
	}

	private HTTPServer.Response deleteComputer(HTTPServer.Request request) {
		Optional<Computer> opComputer = null;
		Gson gson = null;
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NO_CONTENT);
		List<String> prmValues = RESTProcessorUtil.getPrmValues(request.getRequestPattern(), request.getPath());
		if (prmValues.size() == 1) {
			String id = prmValues.get(0);
			switch (id) {
				case "tw-current":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {  // Really? Need that?
						opComputer = nmeaDataComputers.stream()
										.filter(cptr -> cptr instanceof ExtraDataComputer)
										.findFirst();
						response = removeComputerIfPresent(request, opComputer);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "'tw-current' was not found");
					}
					break;
				default:
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						Map<String, String> custom = (Map<String, String>)new Gson().fromJson(stringReader, Object.class);
						opComputer = nmeaDataComputers.stream()
										.filter(cptr -> cptr.getClass().getName().equals(custom.get("cls")))
										.findFirst();
						response = removeComputerIfPresent(request, opComputer);
					} else {
						response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED);
					}
					break;
			}
		} else {
			response.setStatus(HTTPServer.Response.BAD_REQUEST); 
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing path parameter");
		}
		return response;
	}

	private HTTPServer.Response postForwarder(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		Optional<Forwarder> opFwd = null;
		String type = "";
		if (request.getContent() == null || request.getContent().length == 0) {
			response.setStatus(HTTPServer.Response.BAD_REQUEST); 
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
			return response;
		} else {
			Object bean = new GsonBuilder().create().fromJson(new String(request.getContent()), Object.class);
			if (bean instanceof Map) {
				type = ((Map<String, String>) bean).get("type");
			}
		}
		switch (type) {
			case "console":
				// Check existence
				opFwd = nmeaDataForwarders.stream()
								.filter(fwd -> fwd instanceof ConsoleWriter)
								.findFirst();
				if (!opFwd.isPresent()) {
					try {
						Forwarder consoleForwarder = new ConsoleWriter();
						nmeaDataForwarders.add(consoleForwarder);
						response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
						String content = new Gson().toJson(consoleForwarder.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "'console' already exists");
				}
				break;
			case "serial":
				SerialWriter.SerialBean serialJson = new Gson().fromJson(new String(request.getContent()), SerialWriter.SerialBean.class);
				// Check if not there yet.
				opFwd = nmeaDataForwarders.stream()
								.filter(fwd -> fwd instanceof SerialWriter &&
												((SerialWriter) fwd).getPort() == serialJson.getPort())
								.findFirst();
				if (!opFwd.isPresent()) {
					try {
						Forwarder serialForwarder = new SerialWriter(serialJson.getPort(), serialJson.getBR());
						nmeaDataForwarders.add(serialForwarder);
						String content = new Gson().toJson(serialForwarder.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'serial' already exists");
				}
				break;
			case "tcp":
				TCPServer.TCPBean tcpJson = new Gson().fromJson(new String(request.getContent()), TCPServer.TCPBean.class);
				// Check if not there yet.
				opFwd = nmeaDataForwarders.stream()
								.filter(fwd -> fwd instanceof TCPServer &&
												((TCPServer) fwd).getTcpPort() == tcpJson.getPort())
								.findFirst();
				if (!opFwd.isPresent()) {
					try {
						Forwarder tcpForwarder = new TCPServer(tcpJson.getPort());
						nmeaDataForwarders.add(tcpForwarder);
						String content = new Gson().toJson(tcpForwarder.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST); 
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'tcp' already exists");
				}
				break;
			case "gpsd":
				GPSdServer.GPSdBean gpsdJson = new Gson().fromJson(new String(request.getContent()), GPSdServer.GPSdBean.class);
				// Check if not there yet.
				opFwd = nmeaDataForwarders.stream()
								.filter(fwd -> fwd instanceof GPSdServer &&
												((GPSdServer) fwd).getTcpPort() == gpsdJson.getPort())
								.findFirst();
				if (!opFwd.isPresent()) {
					try {
						Forwarder gpsdForwarder = new GPSdServer(gpsdJson.getPort());
						nmeaDataForwarders.add(gpsdForwarder);
						String content = new Gson().toJson(gpsdForwarder.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'gpsd' already exists");
				}
				break;
			case "rmi":
				RMIServer.RMIBean rmiJson = new Gson().fromJson(new String(request.getContent()), RMIServer.RMIBean.class);
				// Check if not there yet.
				opFwd = nmeaDataForwarders.stream()
								.filter(fwd -> fwd instanceof RMIServer &&
												((RMIServer) fwd).getRegistryPort() == rmiJson.getPort())
								.findFirst();
				if (!opFwd.isPresent()) {
					try {
						Forwarder rmiForwarder = new RMIServer(rmiJson.getPort(), rmiJson.getBindingName());
						nmeaDataForwarders.add(rmiForwarder);
						String content = new Gson().toJson(rmiForwarder.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'rmi' already exists");
				}
				break;
			case "file":
				DataFileWriter.DataFileBean fileJson = new Gson().fromJson(new String(request.getContent()), DataFileWriter.DataFileBean.class);
				// Check if not there yet.
				opFwd = nmeaDataForwarders.stream()
								.filter(fwd -> fwd instanceof DataFileWriter &&
												((DataFileWriter) fwd).getLog().equals(fileJson.getLog()))
								.findFirst();
				if (!opFwd.isPresent()) {
					try {
						Forwarder fileForwarder = new DataFileWriter(fileJson.getLog());
						nmeaDataForwarders.add(fileForwarder);
						String content = new Gson().toJson(fileForwarder.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'file' alreacy exists");
				}
				break;
			case "ws":
				WebSocketWriter.WSBean wsJson = new Gson().fromJson(new String(request.getContent()), WebSocketWriter.WSBean.class);
				// Check if not there yet.
				opFwd = nmeaDataForwarders.stream()
								.filter(fwd -> fwd instanceof WebSocketWriter &&
												((WebSocketWriter) fwd).getWsUri() == wsJson.getWsUri())
								.findFirst();
				if (!opFwd.isPresent()) {
					try {
						Forwarder wsForwarder = new WebSocketWriter(wsJson.getWsUri());
						nmeaDataForwarders.add(wsForwarder);
						String content = new Gson().toJson(wsForwarder.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'ws' already exists");
				}
				break;
			case "wsp":
				WebSocketProcessor.WSBean wspJson = new Gson().fromJson(new String(request.getContent()), WebSocketProcessor.WSBean.class);
				// Check if not there yet.
				opFwd = nmeaDataForwarders.stream()
								.filter(fwd -> fwd instanceof WebSocketProcessor &&
												((WebSocketProcessor) fwd).getWsUri() == wspJson.getWsUri())
								.findFirst();
				if (!opFwd.isPresent()) {
					try {
						Forwarder wspForwarder = new WebSocketProcessor(wspJson.getWsUri());
						nmeaDataForwarders.add(wspForwarder);
						String content = new Gson().toJson(wspForwarder.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'wsp' already exists");
				}
				break;
			case "custom":
				String payload = new String(request.getContent());
				Object custom = new Gson().fromJson(payload, Object.class);
				if (custom instanceof Map) {
					Map<String, String> map = (Map<String, String>)custom;
					String forwarderClass = map.get("forwarderClass").trim();
					String propFile = map.get("propFile");
					// Make sure client and reader are not null
					if (forwarderClass == null || forwarderClass.length() == 0) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "Require at least class name.");
						return response;
					}
					// Check Existence
					opFwd = nmeaDataForwarders.stream()
									.filter(fwd -> fwd.getClass().getName().equals(forwarderClass))
									.findFirst();
					if (!opFwd.isPresent()) {
						try {
							// Create dynamic forwarder
							Object dynamic = Class.forName(forwarderClass).newInstance();
							if (dynamic instanceof Forwarder) {
								Forwarder forwarder = (Forwarder)dynamic;

								if (propFile != null && propFile.trim().length() > 0) {
									try {
										Properties properties = new Properties();
										properties.load(new FileReader(propFile));
										forwarder.setProperties(properties);
									} catch (Exception ex) {
										// Send message
										response.setStatus(HTTPServer.Response.BAD_REQUEST);
										RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
										ex.printStackTrace();
									}
								}
								nmeaDataForwarders.add(forwarder);
								String content = new Gson().toJson(forwarder.getBean());
								RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
								response.setPayload(content.getBytes());
							} else {
								// Wrong class
								response.setStatus(HTTPServer.Response.BAD_REQUEST);
								RESTProcessorUtil.addErrorMessageToResponse(response, String.format("Expected a Forwarder, found a [%s] instead.", dynamic.getClass().getName()));
							}
						} catch (Exception ex) {
							response.setStatus(HTTPServer.Response.BAD_REQUEST);
							RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
							ex.printStackTrace();
						}
					} else {
						// Already there
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "this 'custom' channel already exists");
					}
				} else {
					// Unknown object, not a Map...
				}
				break;
			default:
				response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED); 
				RESTProcessorUtil.addErrorMessageToResponse(response, "'" + type + "' not implemented");
				break;
		}
		return response;
	}

	private HTTPServer.Response postChannel(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		Optional<NMEAClient> opClient = null;
		String type = "";
		if (request.getContent() == null || request.getContent().length == 0) {
			response.setStatus(HTTPServer.Response.BAD_REQUEST); 
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
			return response;
		} else {
			Object bean = new GsonBuilder().create().fromJson(new String(request.getContent()), Object.class);
			if (bean instanceof Map) {
				type = ((Map<String, String>) bean).get("type");
			}
		}
		switch (type) {
			case "tcp":
				TCPClient.TCPBean tcpJson = new Gson().fromJson(new String(request.getContent()), TCPClient.TCPBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof SerialClient &&
												((TCPClient.TCPBean) ((TCPClient) channel).getBean()).getPort() == tcpJson.getPort() &&
												((TCPClient.TCPBean) ((TCPClient) channel).getBean()).getHostname().equals(tcpJson.getHostname()))
								.findFirst();
				if (!opClient.isPresent()) {
					try {
						NMEAClient tcpClient = new TCPClient(tcpJson.getDeviceFilters(), tcpJson.getSentenceFilters(), this);
						tcpClient.initClient();
						tcpClient.setReader(new TCPReader(tcpClient.getListeners(), tcpJson.getHostname(), tcpJson.getPort()));
						nmeaDataClients.add(tcpClient);
						tcpClient.startWorking();
						String content = new Gson().toJson(tcpClient.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "ths 'tcp' already exists");
				}
				break;
			case "serial":
				SerialClient.SerialBean serialJson = new Gson().fromJson(new String(request.getContent()), SerialClient.SerialBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof SerialClient &&
												((SerialClient.SerialBean) ((SerialClient) channel).getBean()).getPort().equals(serialJson.getPort()))
								.findFirst();
				if (!opClient.isPresent()) {
					try {
						NMEAClient serialClient = new SerialClient(serialJson.getDeviceFilters(), serialJson.getSentenceFilters(),this);
						serialClient.initClient();
						serialClient.setReader(new SerialReader(serialClient.getListeners(), serialJson.getPort(), serialJson.getBr()));
						nmeaDataClients.add(serialClient);
						serialClient.startWorking();
						String content = new Gson().toJson(serialClient.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'serial' already exists");
				}
				break;
			case "ws":
				WebSocketClient.WSBean wsJson = new Gson().fromJson(new String(request.getContent()), WebSocketClient.WSBean.class);
				// Check if not there yet.
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof WebSocketClient &&
												((WebSocketClient.WSBean) ((WebSocketClient) channel).getBean()).getWsUri().equals(wsJson.getWsUri()))
								.findFirst();
				if (!opClient.isPresent()) {
					try {
						NMEAClient wsClient = new WebSocketClient(wsJson.getDeviceFilters(), wsJson.getSentenceFilters(),this);
						wsClient.initClient();
						wsClient.setReader(new WebSocketReader(wsClient.getListeners(), wsJson.getWsUri()));
						nmeaDataClients.add(wsClient);
						wsClient.startWorking();
						String content = new Gson().toJson(wsClient.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'ws' already exists");
				}
				break;
			case "file":
				DataFileClient.DataFileBean fileJson = new Gson().fromJson(new String(request.getContent()), DataFileClient.DataFileBean.class);
				// Check if not there yet.
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof DataFileClient &&
												((DataFileClient.DataFileBean) ((DataFileClient) channel).getBean()).getFile().equals(fileJson.getFile()))
								.findFirst();
				if (!opClient.isPresent()) {
					try {
						NMEAClient fileClient = new DataFileClient(fileJson.getDeviceFilters(), fileJson.getSentenceFilters(),this);
						fileClient.initClient();
						fileClient.setReader(new DataFileReader(fileClient.getListeners(), fileJson.getFile()));
						nmeaDataClients.add(fileClient);
						fileClient.startWorking();
						String content = new Gson().toJson(fileClient.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'file' already exists");
				}
				break;
			case "bme280":
				BME280Client.BME280Bean bme280Json = new Gson().fromJson(new String(request.getContent()), BME280Client.BME280Bean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof BME280Client)
								.findFirst();
				if (!opClient.isPresent()) {
					try {
						NMEAClient bme280Client = new BME280Client(bme280Json.getDeviceFilters(), bme280Json.getSentenceFilters(),this);
						bme280Client.initClient();
						bme280Client.setReader(new BME280Reader(bme280Client.getListeners()));
						// To do BEFORE startWorking and AFTER setReader
						if (bme280Json.getDevicePrefix() != null) {
							if (bme280Json.getDevicePrefix().trim().length() != 2) {
								throw new RuntimeException(String.format("Device prefix length must be exactly 2. [%s] is not valid", bme280Json.getDevicePrefix().trim()));
							} else {
								((BME280Client)bme280Client).setSpecificDevicePrefix(bme280Json.getDevicePrefix().trim());
							}
						}
						nmeaDataClients.add(bme280Client);
						bme280Client.startWorking();
						String content = new Gson().toJson(bme280Client.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'bme280' already exists");
				}
				break;
			case "htu21df":
				HTU21DFClient.HTU21DFBean htu21dfJson = new Gson().fromJson(new String(request.getContent()), HTU21DFClient.HTU21DFBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof HTU21DFClient)
								.findFirst();
				if (!opClient.isPresent()) {
					try {
						NMEAClient htu21dfClient = new HTU21DFClient(htu21dfJson.getDeviceFilters(), htu21dfJson.getSentenceFilters(),this);
						htu21dfClient.initClient();
						htu21dfClient.setReader(new HTU21DFReader(htu21dfClient.getListeners()));
						// To do BEFORE startWorking and AFTER setReader
						if (htu21dfJson.getDevicePrefix() != null) {
							if (htu21dfJson.getDevicePrefix().trim().length() != 2) {
								throw new RuntimeException(String.format("Device prefix length must be exactly 2. [%s] is not valid", htu21dfJson.getDevicePrefix().trim()));
							} else {
								((HTU21DFClient)htu21dfClient).setSpecificDevicePrefix(htu21dfJson.getDevicePrefix().trim());
							}
						}
						nmeaDataClients.add(htu21dfClient);
						htu21dfClient.startWorking();
						String content = new Gson().toJson(htu21dfClient.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'htu21df' already exists");
				}
				break;
			case "rnd":
				RandomClient.RandomBean rndJson = new Gson().fromJson(new String(request.getContent()), RandomClient.RandomBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof RandomClient)
								.findFirst();
				if (!opClient.isPresent()) {
					try {
						NMEAClient rndClient = new RandomClient(rndJson.getDeviceFilters(), rndJson.getSentenceFilters(),this);
						rndClient.initClient();
						rndClient.setReader(new RandomReader(rndClient.getListeners()));
						nmeaDataClients.add(rndClient);
						rndClient.startWorking();
						String content = new Gson().toJson(rndClient.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST); 
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST); 
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'rnd' already exists");
				}
				break;
			case "custom":
				String payload = new String(request.getContent());
				Object custom = new Gson().fromJson(payload, Object.class);
				if (custom instanceof Map) {
					Map<String, Object> map = (Map<String, Object>)custom;
					String clientClass = ((String)map.get("clientClass")).trim();
					String readerClass = ((String)map.get("readerClass")).trim();
					String propFile = (String)map.get("propFile");
					List<String> deviceFilters = (List<String>)map.get("deviceFilters");
					List<String> sentenceFilters = (List<String>)map.get("sentenceFilters");
					// Make sure client and reader are not null
					if (clientClass == null || clientClass.length() == 0 || readerClass == null || readerClass.length() == 0) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "Require at least both class and reader name.");
						return response;
					}
					// Check Existence
					opClient = nmeaDataClients.stream()
								.filter(channel -> channel.getClass().getName().equals(clientClass))
								.findFirst();
					if (!opClient.isPresent()) {
						try {
							// Create
							String[] devFilters = null;
							String[] senFilters = null;
							if (deviceFilters != null && deviceFilters.size() > 0 && deviceFilters.get(0).length() > 0) {
								List<String> devList = deviceFilters.stream().map(df -> df.trim()).collect(Collectors.toList());
								devFilters = new String[devList.size()];
								devFilters = devList.toArray(devFilters);
							}
							if (sentenceFilters != null && sentenceFilters.size() > 0 && sentenceFilters.get(0).length() > 0) {
								List<String> senList = sentenceFilters.stream().map(df -> df.trim()).collect(Collectors.toList());
								senFilters = new String[senList.size()];
								senFilters = senList.toArray(senFilters);
							}
							Object dynamic = Class.forName(clientClass)
											.getDeclaredConstructor(String[].class, String[].class, Multiplexer.class)
											.newInstance(devFilters, senFilters, this);
							if (dynamic instanceof NMEAClient) {
								NMEAClient nmeaClient = (NMEAClient)dynamic;

								if (propFile != null && propFile.trim().length() > 0) {
									try {
										Properties properties = new Properties();
										properties.load(new FileReader(propFile));
										nmeaClient.setProperties(properties);
									} catch (Exception ex) {
										// Send message
										response.setStatus(HTTPServer.Response.BAD_REQUEST);
										RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
										ex.printStackTrace();
									}
								}
								nmeaClient.initClient();
								NMEAReader reader = null;
								try {
									// Cannot invoke declared constructor with a generic type... :(
									reader = (NMEAReader)Class.forName(readerClass).getDeclaredConstructor(List.class).newInstance(nmeaClient.getListeners());
								} catch (Exception ex) {
									response.setStatus(HTTPServer.Response.BAD_REQUEST);
									RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
									ex.printStackTrace();
								}
								if (reader != null) {
									nmeaClient.setReader(reader);
								}
								nmeaDataClients.add(nmeaClient);
								nmeaClient.startWorking();
								String content = new Gson().toJson(nmeaClient.getBean());
								RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
								response.setPayload(content.getBytes());
							} else {
								// Wrong class
								response.setStatus(HTTPServer.Response.BAD_REQUEST);
								RESTProcessorUtil.addErrorMessageToResponse(response, String.format("Expected an NMEAClient, found a [%s] instead.", dynamic.getClass().getName()));
							}
						} catch (Exception ex) {
							response.setStatus(HTTPServer.Response.BAD_REQUEST);
							RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
							ex.printStackTrace();
						}
					} else {
						// Already there
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "this 'custom' channel already exists");
					}
				} else {
					// Unknown object, not a Map...
				}
				break;
			default:
				response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED);
				break;
		}
		return response;
	}

	private HTTPServer.Response postComputer(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		Optional<Computer> opComputer = null;
		String type = "";
		if (request.getContent() == null || request.getContent().length == 0) {
			response.setStatus(HTTPServer.Response.BAD_REQUEST); 
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
			return response;
		} else {
			Object bean = new GsonBuilder().create().fromJson(new String(request.getContent()), Object.class);
			if (bean instanceof Map) {
				type = ((Map<String, String>) bean).get("type");
			}
		}
		switch (type) {
			case "tw-current":
				ExtraDataComputer.ComputerBean twJson = new Gson().fromJson(new String(request.getContent()), ExtraDataComputer.ComputerBean.class);
				// Check if not there yet.
				opComputer = nmeaDataComputers.stream()
								.filter(channel -> channel instanceof ExtraDataComputer)
								.findFirst();
				if (!opComputer.isPresent()) {
					try {
						String[] timeBuffers = twJson.getTimeBufferLength().split(",");
						List<Long> timeBufferLengths  = Arrays.asList(timeBuffers).stream().map(tbl -> Long.parseLong(tbl.trim())).collect(Collectors.toList());
						// Check duplicates
						for (int i=0; i<timeBufferLengths.size() - 1; i++) {
							for (int j=i+1; j< timeBufferLengths.size(); j++) {
								if (timeBufferLengths.get(i).equals(timeBufferLengths.get(j))) {
									throw new RuntimeException(String.format("Duplicates in time buffer lengths: %d ms.", timeBufferLengths.get(i)));
								}
							}
						}
						Computer twCurrentComputer = new ExtraDataComputer(this, twJson.getPrefix(), timeBufferLengths.toArray(new Long[timeBufferLengths.size()]));
						nmeaDataComputers.add(twCurrentComputer);
						String content = new Gson().toJson(twCurrentComputer.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'computer' already exists");
				}
				break;
			case "custom":
				String payload = new String(request.getContent());
				Object custom = new Gson().fromJson(payload, Object.class);
				if (custom instanceof Map) {
					Map<String, String> map = (Map<String, String>)custom;
					String computerClass = map.get("computerClass").trim();
					String propFile = map.get("propFile");
					// Make sure client and reader are not null
					if (computerClass == null || computerClass.length() == 0) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "Require at least class name.");
						return response;
					}
					// Check Existence
					opComputer = nmeaDataComputers.stream()
									.filter(fwd -> fwd.getClass().getName().equals(computerClass))
									.findFirst();
					if (!opComputer.isPresent()) {
						try {
							// Create
							Object dynamic = Class.forName(computerClass).getDeclaredConstructor(Multiplexer.class).newInstance(this);
							if (dynamic instanceof Computer) {
								Computer computer = (Computer)dynamic;

								if (propFile != null && propFile.trim().length() > 0) {
									try {
										Properties properties = new Properties();
										properties.load(new FileReader(propFile));
										computer.setProperties(properties);
									} catch (Exception ex) {
										// Send message
										response.setStatus(HTTPServer.Response.BAD_REQUEST);
										RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
										ex.printStackTrace();
									}
								}
								nmeaDataComputers.add(computer);
								String content = new Gson().toJson(computer.getBean());
								RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
								response.setPayload(content.getBytes());
							} else {
								// Wrong class
								response.setStatus(HTTPServer.Response.BAD_REQUEST);
								RESTProcessorUtil.addErrorMessageToResponse(response, String.format("Expected a Computer, found a [%s] instead.", dynamic.getClass().getName()));
							}
						} catch (Exception ex) {
							response.setStatus(HTTPServer.Response.BAD_REQUEST);
							RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
							ex.printStackTrace();
						}
					} else {
						// Already there
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "this 'custom' channel already exists");
					}
				} else {
					// Unknown object, not a Map...
				}
				break;
			default:
				response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED); 
				break;
		}
		return response;
	}

	private HTTPServer.Response putChannel(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		Optional<NMEAClient> opClient = null;
		String type = "";
		if (request.getContent() == null || request.getContent().length == 0) {
			response.setStatus(HTTPServer.Response.BAD_REQUEST); 
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
			return response;
		} else {
			Object bean = new GsonBuilder().create().fromJson(new String(request.getContent()), Object.class);
			if (bean instanceof Map) {
				type = ((Map<String, String>) bean).get("type");
			}
			List<String> prmValues = RESTProcessorUtil.getPrmValues(request.getRequestPattern(), request.getPath());
			if (prmValues.size() == 1) {
				String id = prmValues.get(0);
				if (!type.equals(id)) {
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, String.format("path and payload do not match. path:[%s], payload:[%s]", id, type));
					return response;
				}
			} else {
				response.setStatus(HTTPServer.Response.BAD_REQUEST);
				RESTProcessorUtil.addErrorMessageToResponse(response, "required path parameter was not found");
				return response;
			}
		}
		switch (type) {
			case "serial":
				SerialClient.SerialBean serialJson = new Gson().fromJson(new String(request.getContent()), SerialClient.SerialBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof SerialClient &&
												((SerialClient.SerialBean) ((SerialClient) channel).getBean()).getPort().equals(serialJson.getPort()))
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND); 
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'serial' was not found");
				} else { // Then update
					SerialClient serialClient = (SerialClient) opClient.get();
					serialClient.setVerbose(serialJson.getVerbose());
					String content = new Gson().toJson(serialClient.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			case "file":
				DataFileClient.DataFileBean fileJson = new Gson().fromJson(new String(request.getContent()), DataFileClient.DataFileBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof DataFileClient &&
												((DataFileClient.DataFileBean) ((DataFileClient) channel).getBean()).getFile().equals(fileJson.getFile()))
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND); 
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'file' was not found");
				} else { // Then update
					DataFileClient dataFileClient = (DataFileClient) opClient.get();
					dataFileClient.setVerbose(fileJson.getVerbose());
					String content = new Gson().toJson(dataFileClient.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			case "tcp":
				TCPClient.TCPBean tcpJson = new Gson().fromJson(new String(request.getContent()), TCPClient.TCPBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof TCPClient &&
												((TCPClient.TCPBean) ((TCPClient) channel).getBean()).getHostname().equals(tcpJson.getHostname()) &&
												((TCPClient.TCPBean) ((TCPClient) channel).getBean()).getPort() == tcpJson.getPort())
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND); 
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'tcp' was not found");
				} else { // Then update
					TCPClient tcpClient = (TCPClient) opClient.get();
					tcpClient.setVerbose(tcpJson.getVerbose());
					String content = new Gson().toJson(tcpClient.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			case "ws":
				WebSocketClient.WSBean wsJson = new Gson().fromJson(new String(request.getContent()), WebSocketClient.WSBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof WebSocketClient &&
												((WebSocketClient.WSBean) ((WebSocketClient) channel).getBean()).getWsUri().equals(wsJson.getWsUri()))
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND); 
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'ws' was not found");
				} else { // Then update
					WebSocketClient webSocketClient = (WebSocketClient) opClient.get();
					webSocketClient.setVerbose(wsJson.getVerbose());
					String content = new Gson().toJson(webSocketClient.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			case "bme280":
				BME280Client.BME280Bean bme280Json = new Gson().fromJson(new String(request.getContent()), BME280Client.BME280Bean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof BME280Client)
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND); 
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'bme280' was not found");
				} else { // Then update
					BME280Client bme280Client = (BME280Client) opClient.get();
					bme280Client.setVerbose(bme280Json.getVerbose());
					String content = new Gson().toJson(bme280Client.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			case "htu21df":
				HTU21DFClient.HTU21DFBean htu21dfJson = new Gson().fromJson(new String(request.getContent()), HTU21DFClient.HTU21DFBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof HTU21DFClient)
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND); 
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'htu21df' was not found");
				} else { // Then update
					HTU21DFClient htu21DFClient = (HTU21DFClient) opClient.get();
					htu21DFClient.setVerbose(htu21dfJson.getVerbose());
					String content = new Gson().toJson(htu21DFClient.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			case "rnd":
				RandomClient.RandomBean rndJson = new Gson().fromJson(new String(request.getContent()), RandomClient.RandomBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof RandomClient)
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND); 
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'rnd' was not found");
				} else { // Then update
					RandomClient randomClient = (RandomClient) opClient.get();
					randomClient.setVerbose(rndJson.getVerbose());
					String content = new Gson().toJson(randomClient.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			default:
				Map<String, Object> custom = (Map<String, Object>)new Gson().fromJson(new String(request.getContent()), Object.class);
				opClient = nmeaDataClients.stream()
								.filter(cptr -> cptr.getClass().getName().equals(custom.get("cls")))
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND);
					RESTProcessorUtil.addErrorMessageToResponse(response, "'custom' not found");
				} else { // Then update
					NMEAClient nmeaClient = opClient.get();
					boolean verbose = ((Boolean)custom.get("verbose")).booleanValue();
					nmeaClient.setVerbose(verbose);
					String content = new Gson().toJson(nmeaClient.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
//			response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED);
				break;
		}
		return response;
	}

	private HTTPServer.Response putForwarder(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		Optional<NMEAClient> opClient = null;
		String type = "";
		if (request.getContent() == null || request.getContent().length == 0) {
			response.setStatus(HTTPServer.Response.BAD_REQUEST); 
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
			return response;
		} else {
			Object bean = new GsonBuilder().create().fromJson(new String(request.getContent()), Object.class);
			if (bean instanceof Map) {
				type = ((Map<String, String>) bean).get("type");
			}
			List<String> prmValues = RESTProcessorUtil.getPrmValues(request.getRequestPattern(), request.getPath());
			if (prmValues.size() == 1) {
				String id = prmValues.get(0);
				if (!type.equals(id)) {
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, String.format("path and payload do not match. path:[%s], payload:[%s]", id, type));
					return response;
				}
			} else {
				response.setStatus(HTTPServer.Response.BAD_REQUEST);
				RESTProcessorUtil.addErrorMessageToResponse(response, "required path parameter was not found");
				return response;
			}
		}
		switch (type) {
			default:
				response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED); 
				break;
		}
		return response;
	}

	private HTTPServer.Response putComputer(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		Optional<Computer> opComputer = null;
		String type = "";
		if (request.getContent() == null || request.getContent().length == 0) {
			response.setStatus(HTTPServer.Response.BAD_REQUEST); 
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
			return response;
		} else {
			Object bean = new GsonBuilder().create().fromJson(new String(request.getContent()), Object.class);
			if (bean instanceof Map) {
				type = ((Map<String, String>) bean).get("type");
			}
			List<String> prmValues = RESTProcessorUtil.getPrmValues(request.getRequestPattern(), request.getPath());
			if (prmValues.size() == 1) {
				String id = prmValues.get(0);
				if (!type.equals(id)) {
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, String.format("path and payload do not match. path:[%s], payload:[%s]", id, type));
					return response;
				}
			} else {
				response.setStatus(HTTPServer.Response.BAD_REQUEST);
				RESTProcessorUtil.addErrorMessageToResponse(response, "required path parameter was not found");
				return response;
			}
		}
		switch (type) {
			case "tw-current":
				ExtraDataComputer.ComputerBean twJson = new Gson().fromJson(new String(request.getContent()), ExtraDataComputer.ComputerBean.class);
				opComputer = nmeaDataComputers.stream()
								.filter(cptr -> cptr instanceof ExtraDataComputer)
								.findFirst();
				if (!opComputer.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND); 
					RESTProcessorUtil.addErrorMessageToResponse(response, "'tw-current' not found");
				} else { // Then update
					ExtraDataComputer computer = (ExtraDataComputer) opComputer.get();
					computer.setVerbose(twJson.isVerbose());
					computer.setPrefix(twJson.getPrefix());
					String content = new Gson().toJson(computer.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			default:
				Map<String, Object> custom = (Map<String, Object>)new Gson().fromJson(new String(request.getContent()), Object.class);
				opComputer = nmeaDataComputers.stream()
								.filter(cptr -> cptr.getClass().getName().equals(custom.get("cls")))
								.findFirst();
				if (!opComputer.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND);
					RESTProcessorUtil.addErrorMessageToResponse(response, "'custom' not found");
				} else { // Then update
					Computer computer = opComputer.get();
					boolean verbose = ((Boolean)custom.get("verbose")).booleanValue();
					computer.setVerbose(verbose);
					String content = new Gson().toJson(computer.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
//			response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED);
				break;
		}
		return response;
	}

	private HTTPServer.Response putMuxVerbose(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		List<String> prmValues = RESTProcessorUtil.getPrmValues(request.getRequestPattern(), request.getPath());
		if (prmValues.size() != 1) {
			response.setStatus(HTTPServer.Response.BAD_REQUEST);
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing path parameter");
			return response;
		}
		boolean newValue = "on".equals(prmValues.get(0));
		this.verbose = newValue;
		String content = "";
		RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
		response.setPayload(content.getBytes());

		return response;
	}

	private HTTPServer.Response getCache(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);

		NMEADataCache cache = ApplicationContext.getInstance().getDataCache();

		JsonElement jsonElement = null;
		try {
			// Calculate VMG(s)
			NMEAUtils.calculateVMGs(cache);

			jsonElement = new Gson().toJsonTree(cache);
			((JsonObject) jsonElement).remove(NMEADataCache.DEVIATION_DATA); // Useless for the client.
		} catch (Exception ex) {
			System.err.println("getCache:" + ex.toString());
		}
		String content = jsonElement != null ? jsonElement.toString() : "";
		RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
		response.setPayload(content.getBytes());

		return response;
	}

	private HTTPServer.Response resetCache(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NO_CONTENT);

		NMEADataCache cache = ApplicationContext.getInstance().getDataCache();
		cache.reset();
		// Also reset computers
		nmeaDataComputers.stream()
						.filter(channel -> channel instanceof ExtraDataComputer)
						.forEach(extraDataComputer -> ((ExtraDataComputer)extraDataComputer).resetCurrentComputers());
		RESTProcessorUtil.generateHappyResponseHeaders(response, 0);

		return response;
	}

	private HTTPServer.Response getOperationList(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		List<Object> channelList = getInputChannelList();
		Operation[] channelArray = operations.stream()
						.collect(Collectors.toList())
						.toArray(new Operation[operations.size()]);
		String content = new Gson().toJson(channelArray);
		RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
		response.setPayload(content.getBytes());
		return response;
	}

	/**
	 * Use this as a temporary placeholder when creating a new operation.
	 * @param request
	 * @return
	 */
	private HTTPServer.Response emptyOperation(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);

		return response;
	}

	/**
	 * Implements the management of the REST requests.
	 * Dedicated Admin Server.
	 *
	 * @param request the parsed request.
	 * @return the response, along with its HTTP status code.
	 */
	@Override
	public HTTPServer.Response onRequest(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NOT_IMPLEMENTED);
		response = processRequest(request, response); // All the skill is here.
		if (this.verbose) {
			System.out.println("======================================");
			System.out.println("Request :\n" + request.toString());
			System.out.println("Response :\n" + response.toString());
			System.out.println("======================================");
		}
		return response;
	}

	private HTTPServer.Response removeForwarderIfPresent(HTTPServer.Request request, Optional<Forwarder> opFwd) {
		HTTPServer.Response response;
		if (opFwd.isPresent()) {
			Forwarder forwarder = opFwd.get();
			forwarder.close();
			nmeaDataForwarders.remove(forwarder);
			response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NO_CONTENT);
		} else {
			response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NOT_FOUND);
			RESTProcessorUtil.addErrorMessageToResponse(response, "forwarder not found");
		}
		return response;
	}

	private HTTPServer.Response removeChannelIfPresent(HTTPServer.Request request, Optional<NMEAClient> nmeaClient) {
		HTTPServer.Response response;
		if (nmeaClient.isPresent()) {
			NMEAClient client = nmeaClient.get();
			client.stopDataRead();
			nmeaDataClients.remove(client);
			response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NO_CONTENT);
		} else {
			response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NOT_FOUND);
			RESTProcessorUtil.addErrorMessageToResponse(response, "channel not found");
		}
		return response;
	}

	private HTTPServer.Response removeComputerIfPresent(HTTPServer.Request request, Optional<Computer> nmeaComputer) {
		HTTPServer.Response response;
		if (nmeaComputer.isPresent()) {
			Computer computer = nmeaComputer.get();
			computer.close();
			nmeaDataComputers.remove(computer);
			response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NO_CONTENT);
		} else {
			response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NOT_FOUND);
			RESTProcessorUtil.addErrorMessageToResponse(response, "computer not found");
		}
		return response;
	}

	private static List<String> getSerialPortList() {
		List<String> portList = new ArrayList<>();
		// Opening Serial port
		Enumeration enumeration = CommPortIdentifier.getPortIdentifiers();
		while (enumeration.hasMoreElements()) {
			CommPortIdentifier cpi = (CommPortIdentifier) enumeration.nextElement();
			portList.add(cpi.getName());
		}
		return portList;
	}

	private List<Object> getInputChannelList() {
		return nmeaDataClients.stream().map(nmea -> nmea.getBean()).collect(Collectors.toList());
	}

	private List<Object> getForwarderList() {
		return nmeaDataForwarders.stream().map(fwd -> fwd.getBean()).collect(Collectors.toList());
	}

	private List<Object> getComputerList() {
		return nmeaDataComputers.stream().map(cptr -> cptr.getBean()).collect(Collectors.toList());
	}

	@Override
	public synchronized void onData(String mess) {
		if (verbose) {
			System.out.println("==== From MUX: " + mess);
			DumpUtil.displayDualDump(mess);
			System.out.println("==== End Mux =============");
		}
		// Cache, if initialized
		if (ApplicationContext.getInstance().getDataCache() != null) {
			ApplicationContext.getInstance().getDataCache().parseAndFeed(mess);
		}
		// Computers. Must go first, as a computer may refeed the present onData method.
		nmeaDataComputers.stream()
						.forEach(computer -> {
							computer.write(mess.getBytes());
						});
		// Forwarders
		nmeaDataForwarders.stream()
						.forEach(fwd -> {
							try {
								fwd.write((mess.trim() + NMEAParser.STANDARD_NMEA_EOS).getBytes());
							} catch (Exception e) {
								e.printStackTrace();
							}
						});
	}

	private final static NumberFormat MUX_IDX_FMT = new DecimalFormat("00");
	private boolean verbose = false;

	/**
	 * Constructor.
	 * @param muxProps Initial config. See {@link #main(String...)} method.
	 */
	public GenericNMEAMultiplexer(Properties muxProps) {

		// Check duplicates in operation list. Barfs if duplicate is found.
		for (int i = 0; i < operations.size(); i++) {
			for (int j = i + 1; j < operations.size(); j++) {
				if (operations.get(i).getVerb().equals(operations.get(j).getVerb()) &&
								RESTProcessorUtil.pathsAreIndentical(operations.get(i).getPath(), operations.get(j).getPath())) {
					throw new RuntimeException(String.format("Duplicate entry in operations list %s %s", operations.get(i).getVerb(), operations.get(i).getPath()));
				}
			}
		}

		// Read initial config from the properties file. See the main method.
		verbose = "true".equals(System.getProperty("mux.data.verbose", "false")); // Initial verbose.
		int muxIdx = 1;
		boolean thereIsMore = true;
		// 1 - Input channels
		while (thereIsMore) {
			String classProp = String.format("mux.%s.cls", MUX_IDX_FMT.format(muxIdx));
			String cls = muxProps.getProperty(classProp);
			if (cls != null) { // Dynamic loading
				try {
					// Devices and Sentences filters.
					String deviceFilters = "";
					String sentenceFilters = "";
					deviceFilters = muxProps.getProperty(String.format("mux.%s.device.filters", MUX_IDX_FMT.format(muxIdx)), "");
					sentenceFilters = muxProps.getProperty(String.format("mux.%s.sentence.filters", MUX_IDX_FMT.format(muxIdx)), "");
					Object dynamic = Class.forName(cls)
									.getDeclaredConstructor(String[].class, String[].class, Multiplexer.class)
									.newInstance(
													deviceFilters.trim().length() > 0 ? deviceFilters.split(",") : null,
													sentenceFilters.trim().length() > 0 ? sentenceFilters.split(",") : null,
													this);
					if (dynamic instanceof NMEAClient) {
						NMEAClient nmeaClient = (NMEAClient)dynamic;
						String propProp = String.format("mux.%s.properties", MUX_IDX_FMT.format(muxIdx));
						String propFileName = muxProps.getProperty(propProp);
						if (propFileName != null) {
							try {
								Properties properties = new Properties();
								properties.load(new FileReader(propFileName));
								nmeaClient.setProperties(properties);
							} catch (Exception ex) {
								ex.printStackTrace();
							}
						}
						nmeaClient.initClient();
						NMEAReader reader = null;
						try {
							String readerProp = String.format("mux.%s.reader", MUX_IDX_FMT.format(muxIdx));
							String readerClass = muxProps.getProperty(readerProp);
							// Cannot invoke declared constructor with a generic type... :(
							reader = (NMEAReader)Class.forName(readerClass).getDeclaredConstructor(List.class).newInstance(nmeaClient.getListeners());
						} catch (Exception ex) {
							ex.printStackTrace();
						}
						if (reader != null) {
							nmeaClient.setReader(reader);
						}
						nmeaDataClients.add(nmeaClient);
					} else {
						throw new RuntimeException(String.format("Expected an NMEAClient, found a [%s]", dynamic.getClass().getName()));
					}
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			} else {
				String typeProp = String.format("mux.%s.type", MUX_IDX_FMT.format(muxIdx));
				String type = muxProps.getProperty(typeProp);
				if (type == null) {
					thereIsMore = false;
				} else {
					String deviceFilters = "";
					String sentenceFilters = "";
					switch (type) {
						case "serial":
							try {
								String serialPort = muxProps.getProperty(String.format("mux.%s.port", MUX_IDX_FMT.format(muxIdx)));
								String br = muxProps.getProperty(String.format("mux.%s.baudrate", MUX_IDX_FMT.format(muxIdx)));
								deviceFilters = muxProps.getProperty(String.format("mux.%s.device.filters", MUX_IDX_FMT.format(muxIdx)), "");
								sentenceFilters = muxProps.getProperty(String.format("mux.%s.sentence.filters", MUX_IDX_FMT.format(muxIdx)), "");
								NMEAClient serialClient = new SerialClient(
												deviceFilters.trim().length() > 0 ? deviceFilters.split(",") : null,
												sentenceFilters.trim().length() > 0 ? sentenceFilters.split(",") : null,
												this);
								serialClient.initClient();
								serialClient.setReader(new SerialReader(serialClient.getListeners(), serialPort, Integer.parseInt(br)));
								nmeaDataClients.add(serialClient);
							} catch (Exception e) {
								e.printStackTrace();
							}
							break;
						case "tcp":
							try {
								String tcpPort = muxProps.getProperty(String.format("mux.%s.port", MUX_IDX_FMT.format(muxIdx)));
								String tcpServer = muxProps.getProperty(String.format("mux.%s.server", MUX_IDX_FMT.format(muxIdx)));
								deviceFilters = muxProps.getProperty(String.format("mux.%s.device.filters", MUX_IDX_FMT.format(muxIdx)), "");
								sentenceFilters = muxProps.getProperty(String.format("mux.%s.sentence.filters", MUX_IDX_FMT.format(muxIdx)), "");
								NMEAClient tcpClient = new TCPClient(
												deviceFilters.trim().length() > 0 ? deviceFilters.split(",") : null,
												sentenceFilters.trim().length() > 0 ? sentenceFilters.split(",") : null,
												this);
								tcpClient.initClient();
								tcpClient.setReader(new TCPReader(tcpClient.getListeners(), tcpServer, Integer.parseInt(tcpPort)));
								nmeaDataClients.add(tcpClient);
							} catch (Exception e) {
								e.printStackTrace();
							}
							break;
						case "file":
							try {
								String filename = muxProps.getProperty(String.format("mux.%s.filename", MUX_IDX_FMT.format(muxIdx)));
								deviceFilters = muxProps.getProperty(String.format("mux.%s.device.filters", MUX_IDX_FMT.format(muxIdx)), "");
								sentenceFilters = muxProps.getProperty(String.format("mux.%s.sentence.filters", MUX_IDX_FMT.format(muxIdx)), "");
								NMEAClient fileClient = new DataFileClient(
												deviceFilters.trim().length() > 0 ? deviceFilters.split(",") : null,
												sentenceFilters.trim().length() > 0 ? sentenceFilters.split(",") : null,
												this);
								fileClient.initClient();
								fileClient.setReader(new DataFileReader(fileClient.getListeners(), filename));
								nmeaDataClients.add(fileClient);
							} catch (Exception e) {
								e.printStackTrace();
							}
							break;
						case "ws":
							try {
								String wsUri = muxProps.getProperty(String.format("mux.%s.wsuri", MUX_IDX_FMT.format(muxIdx)));
								deviceFilters = muxProps.getProperty(String.format("mux.%s.device.filters", MUX_IDX_FMT.format(muxIdx)), "");
								sentenceFilters = muxProps.getProperty(String.format("mux.%s.sentence.filters", MUX_IDX_FMT.format(muxIdx)), "");
								NMEAClient wsClient = new WebSocketClient(
												deviceFilters.trim().length() > 0 ? deviceFilters.split(",") : null,
												sentenceFilters.trim().length() > 0 ? sentenceFilters.split(",") : null,
												this);
								wsClient.initClient();
								wsClient.setReader(new WebSocketReader(wsClient.getListeners(), wsUri));
								nmeaDataClients.add(wsClient);
							} catch (Exception e) {
								e.printStackTrace();
							}
							break;
						case "htu21df": // Humidity & Temperature sensor
							try {
								deviceFilters = muxProps.getProperty(String.format("mux.%s.device.filters", MUX_IDX_FMT.format(muxIdx)), "");
								sentenceFilters = muxProps.getProperty(String.format("mux.%s.sentence.filters", MUX_IDX_FMT.format(muxIdx)), "");
								String htu21dfDevicePrefix = muxProps.getProperty(String.format("mux.%s.device.prefix", MUX_IDX_FMT.format(muxIdx)), "");
								NMEAClient htu21dfClient = new HTU21DFClient(
												deviceFilters.trim().length() > 0 ? deviceFilters.split(",") : null,
												sentenceFilters.trim().length() > 0 ? sentenceFilters.split(",") : null,
												this);
								htu21dfClient.initClient();
								htu21dfClient.setReader(new HTU21DFReader(htu21dfClient.getListeners()));
								// Important: after the setReader
								if (htu21dfDevicePrefix.trim().length() > 0) {
									if (htu21dfDevicePrefix.trim().length() == 2) {
										((HTU21DFClient) htu21dfClient).setSpecificDevicePrefix(htu21dfDevicePrefix.trim());
									} else {
										throw new RuntimeException(String.format("Bad prefix [%s] for HTU21DF. Must be 2 character long, exactly.", htu21dfDevicePrefix.trim()));
									}
								}
								nmeaDataClients.add(htu21dfClient);
							} catch (Exception e) {
								e.printStackTrace();
							} catch (Error err) {
								err.printStackTrace();
							}
							break;
						case "rnd": // Random generator, for debugging
							try {
								deviceFilters = muxProps.getProperty(String.format("mux.%s.device.filters", MUX_IDX_FMT.format(muxIdx)), "");
								sentenceFilters = muxProps.getProperty(String.format("mux.%s.sentence.filters", MUX_IDX_FMT.format(muxIdx)), "");
								NMEAClient rndClient = new RandomClient(
												deviceFilters.trim().length() > 0 ? deviceFilters.split(",") : null,
												sentenceFilters.trim().length() > 0 ? sentenceFilters.split(",") : null,
												this);
								rndClient.initClient();
								rndClient.setReader(new RandomReader(rndClient.getListeners()));
								nmeaDataClients.add(rndClient);
							} catch (Exception e) {
								e.printStackTrace();
							} catch (Error err) {
								err.printStackTrace();
							}
							break;
						case "bme280": // Humidity, Temperature, Pressure
							try {
								deviceFilters = muxProps.getProperty(String.format("mux.%s.device.filters", MUX_IDX_FMT.format(muxIdx)), "");
								sentenceFilters = muxProps.getProperty(String.format("mux.%s.sentence.filters", MUX_IDX_FMT.format(muxIdx)), "");
								String bme280DevicePrefix = muxProps.getProperty(String.format("mux.%s.device.prefix", MUX_IDX_FMT.format(muxIdx)), "");
								NMEAClient bme280Client = new BME280Client(
												deviceFilters.trim().length() > 0 ? deviceFilters.split(",") : null,
												sentenceFilters.trim().length() > 0 ? sentenceFilters.split(",") : null,
												this);
								bme280Client.initClient();
								bme280Client.setReader(new BME280Reader(bme280Client.getListeners()));
								// Important: after the setReader
								if (bme280DevicePrefix.trim().length() > 0) {
									if (bme280DevicePrefix.trim().length() == 2) {
										((BME280Client) bme280Client).setSpecificDevicePrefix(bme280DevicePrefix.trim());
									} else {
										throw new RuntimeException(String.format("Bad prefix [%s] for BME280. Must be 2 character long, exactly.", bme280DevicePrefix.trim()));
									}
								}
								nmeaDataClients.add(bme280Client);
							} catch (Exception e) {
								e.printStackTrace();
							} catch (Error err) {
								err.printStackTrace();
							}
							break;
						case "bmp180": // Temperature, Pressure
						case "lsm303": // 3D magnetometer
						case "batt":   // Battery Voltage, use XDR
						default:
							throw new RuntimeException(String.format("mux type [%s] not supported yet.", type));
					}
				}
			}
			muxIdx++;
		}

		// Data Cache
		if ("true".equals(muxProps.getProperty("init.cache", "false"))) {
			try {
				String deviationFile = muxProps.getProperty("deviation.file.name", "zero-deviation.csv");
				double maxLeeway = Double.parseDouble(muxProps.getProperty("max.leeway", "0"));
				double bspFactor = Double.parseDouble(muxProps.getProperty("bsp.factor", "1"));
				double awsFactor = Double.parseDouble(muxProps.getProperty("aws.factor", "1"));
				double awaOffset = Double.parseDouble(muxProps.getProperty("awa.offset", "0"));
				double hdgOffset = Double.parseDouble(muxProps.getProperty("hdg.offset", "0"));
				double defaultDeclination = Double.parseDouble(muxProps.getProperty("default.declination", "0"));
				int damping = Integer.parseInt(muxProps.getProperty("damping", "1"));
				ApplicationContext.getInstance().initCache(deviationFile, maxLeeway, bspFactor, awsFactor, awaOffset, hdgOffset, defaultDeclination, damping);
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}

		thereIsMore = true;
		int fwdIdx = 1;
		// 2 - Output channels, aka forwarders
		while (thereIsMore) {
			String classProp = String.format("forward.%s.cls", MUX_IDX_FMT.format(fwdIdx));
			String cls = muxProps.getProperty(classProp);
			if (cls != null) { // Dynamic loading
				try {
					Object dynamic = Class.forName(cls).newInstance();
					if (dynamic instanceof Forwarder) {
						Forwarder forwarder = (Forwarder)dynamic;
						String propProp = String.format("forward.%s.properties", MUX_IDX_FMT.format(fwdIdx));
						String propFileName = muxProps.getProperty(propProp);
						if (propFileName != null) {
							try {
								Properties properties = new Properties();
								properties.load(new FileReader(propFileName));
								forwarder.setProperties(properties);
							} catch (Exception ex) {
								ex.printStackTrace();
							}
						}
						nmeaDataForwarders.add(forwarder);
					} else {
						throw new RuntimeException(String.format("Expected a Forwarder, found a [%s]", dynamic.getClass().getName()));
					}
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			} else {
				String typeProp = String.format("forward.%s.type", MUX_IDX_FMT.format(fwdIdx));
				String type = muxProps.getProperty(typeProp);
				if (type == null) {
					thereIsMore = false;
				} else {
					switch (type) {
						case "serial":
							String serialPort = muxProps.getProperty(String.format("forward.%s.port", MUX_IDX_FMT.format(fwdIdx)));
							int baudrate = Integer.parseInt(muxProps.getProperty(String.format("forward.%s.baudrate", MUX_IDX_FMT.format(fwdIdx))));
							try {
								Forwarder serialForwarder = new SerialWriter(serialPort, baudrate);
								nmeaDataForwarders.add(serialForwarder);
							} catch (Exception ex) {
								ex.printStackTrace();
							}
							break;
						case "tcp":
							String tcpPort = muxProps.getProperty(String.format("forward.%s.port", MUX_IDX_FMT.format(fwdIdx)));
							try {
								Forwarder tcpForwarder = new TCPServer(Integer.parseInt(tcpPort));
								nmeaDataForwarders.add(tcpForwarder);
							} catch (Exception ex) {
								ex.printStackTrace();
							}
							break;
						case "gpsd":
							String gpsdPort = muxProps.getProperty(String.format("forward.%s.port", MUX_IDX_FMT.format(fwdIdx)));
							try {
								Forwarder gpsdForwarder = new GPSdServer(Integer.parseInt(gpsdPort));
								nmeaDataForwarders.add(gpsdForwarder);
							} catch (Exception ex) {
								ex.printStackTrace();
							}
							break;
						case "file":
							String fName = muxProps.getProperty(String.format("forward.%s.filename", MUX_IDX_FMT.format(fwdIdx)));
							try {
								Forwarder fileForwarder = new DataFileWriter(fName);
								nmeaDataForwarders.add(fileForwarder);
							} catch (Exception ex) {
								ex.printStackTrace();
							}
							break;
						case "ws":
							String wsUri = muxProps.getProperty(String.format("forward.%s.wsuri", MUX_IDX_FMT.format(fwdIdx)));
							try {
								Forwarder wsForwarder = new WebSocketWriter(wsUri);
								nmeaDataForwarders.add(wsForwarder);
							} catch (Exception ex) {
								ex.printStackTrace();
							}
							break;
						case "wsp":
							String wspUri = muxProps.getProperty(String.format("forward.%s.wsuri", MUX_IDX_FMT.format(fwdIdx)));
							try {
								Forwarder wsForwarder = new WebSocketProcessor(wspUri);
								nmeaDataForwarders.add(wsForwarder);
							} catch (Exception ex) {
								ex.printStackTrace();
							}
							break;
						case "console":
							try {
								Forwarder consoleForwarder = new ConsoleWriter();
								nmeaDataForwarders.add(consoleForwarder);
							} catch (Exception ex) {
								ex.printStackTrace();
							}
							break;
						case "rmi":
							String rmiPort = muxProps.getProperty(String.format("forward.%s.port", MUX_IDX_FMT.format(fwdIdx)));
							String rmiName = muxProps.getProperty(String.format("forward.%s.name", MUX_IDX_FMT.format(fwdIdx)));
							try {
								Forwarder rmiServerForwarder;
								if (rmiName != null && rmiName.trim().length() > 0) {
									rmiServerForwarder = new RMIServer(Integer.parseInt(rmiPort), rmiName);
								} else {
									rmiServerForwarder = new RMIServer(Integer.parseInt(rmiPort));
								}
								nmeaDataForwarders.add(rmiServerForwarder);
							} catch (Exception ex) {
								ex.printStackTrace();
							}
							break;
						default:
							throw new RuntimeException(String.format("forward type [%s] not supported yet.", type));
					}
				}
			}
			fwdIdx++;
		}
		// Init cache (for Computers).
		if ("true".equals(muxProps.getProperty("init.cache", "false"))) {
			try {
				// If there is a cache, then let's see what computers to start.
				thereIsMore = true;
				int cptrIdx = 1;
				// 3 - Computers
				while (thereIsMore) {
					String classProp = String.format("computer.%s.cls", MUX_IDX_FMT.format(cptrIdx));
					String cls = muxProps.getProperty(classProp);
					if (cls != null) { // Dynamic loading
						try {
							Object dynamic = Class.forName(cls).getDeclaredConstructor(Multiplexer.class).newInstance(this);
							if (dynamic instanceof Computer) {
								Computer computer = (Computer)dynamic;
								String propProp = String.format("computer.%s.properties", MUX_IDX_FMT.format(cptrIdx));
								String propFileName = muxProps.getProperty(propProp);
								if (propFileName != null) {
									try {
										Properties properties = new Properties();
										properties.load(new FileReader(propFileName));
										computer.setProperties(properties);
									} catch (Exception ex) {
										ex.printStackTrace();
									}
								}
								nmeaDataComputers.add(computer);
							} else {
								throw new RuntimeException(String.format("Expected a Computer, found a [%s]", dynamic.getClass().getName()));
							}
						} catch (Exception ex) {
							ex.printStackTrace();
						}
					} else {
						String typeProp = String.format("computer.%s.type", MUX_IDX_FMT.format(cptrIdx));
						String type = muxProps.getProperty(typeProp);
						if (type == null) {
							thereIsMore = false;
						} else {
							switch (type) {
								case "tw-current":
									String prefix = muxProps.getProperty(String.format("computer.%s.prefix", MUX_IDX_FMT.format(cptrIdx)), "OS");
									String[] timeBuffers = muxProps.getProperty(String.format("computer.%s.time.buffer.length", MUX_IDX_FMT.format(cptrIdx)), "600000").split(",");
									List<Long> timeBufferLengths = Arrays.asList(timeBuffers).stream().map(tbl -> Long.parseLong(tbl.trim())).collect(Collectors.toList());
									// Check duplicates
									for (int i = 0; i < timeBufferLengths.size() - 1; i++) {
										for (int j = i + 1; j < timeBufferLengths.size(); j++) {
											if (timeBufferLengths.get(i).equals(timeBufferLengths.get(j))) {
												throw new RuntimeException(String.format("Duplicates in time buffer lengths: %d ms.", timeBufferLengths.get(i)));
											}
										}
									}
									try {
										Computer twCurrentComputer = new ExtraDataComputer(this, prefix, timeBufferLengths.toArray(new Long[timeBufferLengths.size()]));
										nmeaDataComputers.add(twCurrentComputer);
									} catch (Exception ex) {
										ex.printStackTrace();
									}
									break;
								default:
									System.err.println(String.format("Computer type [%s] not supported.", type));
									break;
							}
						}
					}
					cptrIdx++;
				}
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}

		Runtime.getRuntime().addShutdownHook(new Thread("Multiplexer shutdown hook") {
			public void run() {
				System.out.println("Shutting down multiplexer nicely.");
				nmeaDataClients.stream()
								.forEach(client -> client.stopDataRead());
				nmeaDataForwarders.stream()
								.forEach(fwd -> fwd.close());
				nmeaDataComputers.stream()
								.forEach(comp -> comp.close());
				if (adminServer != null) {
					adminServer.stopRunning();
				}
			}
		});

		nmeaDataClients.stream()
						.forEach(client -> {
							try {
								client.startWorking();
							} catch (Exception ex) {
								ex.printStackTrace();
							}
						});
	}

	public void startAdminServer(int port) {
		try {
			this.adminServer = new HTTPServer(port, this);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Start the Multiplexer from here.
	 *
	 * @param args unused.
	 */
	public static void main(String... args) {

		String propertiesFile = System.getProperty("mux.properties", "nmea.mux.properties");

		Properties definitions = new Properties();
		File propFile = new File(propertiesFile);
		if (!propFile.exists()) {
			throw new RuntimeException(String.format("File [%s] not found", propertiesFile));
		} else {
			try {
				definitions.load(new FileReader(propFile));
			} catch (IOException ioe) {
				ioe.printStackTrace();
			}
		}

		GenericNMEAMultiplexer mux = new GenericNMEAMultiplexer(definitions);

		// with.http.server=yes
		// http.port=9999
		if ("yes".equals(definitions.getProperty("with.http.server", "no"))) {
			mux.startAdminServer(Integer.parseInt(definitions.getProperty("http.port", "9999")));
		}
	}
}
