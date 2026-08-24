package pl.rikitiki.im.xmpp.jingle;

import java.util.ArrayList;
import java.util.List;

import pl.rikitiki.im.xml.Element;

/**
 * One <service/> entry from a XEP-0215 (External Service Discovery) response,
 * e.g. a STUN or TURN server advertised by the account's own XMPP server.
 */
public class ExternalService {

	private String type;
	private String host;
	private int port;
	private String transport;
	private String username;
	private String password;
	private boolean restricted;

	public String getType() {
		return type;
	}

	public String getHost() {
		return host;
	}

	public int getPort() {
		return port;
	}

	public String getTransport() {
		return transport;
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}

	public boolean isRestricted() {
		return restricted;
	}

	public static List<ExternalService> parse(final Element services) {
		final List<ExternalService> parsed = new ArrayList<>();
		if (services == null) {
			return parsed;
		}
		for (final Element child : services.getChildren()) {
			if (!"service".equals(child.getName())) {
				continue;
			}
			final ExternalService service = new ExternalService();
			service.type = child.getAttribute("type");
			service.host = child.getAttribute("host");
			service.transport = child.getAttribute("transport");
			service.username = child.getAttribute("username");
			service.password = child.getAttribute("password");
			service.restricted = "true".equals(child.getAttribute("restricted"));
			final String port = child.getAttribute("port");
			try {
				service.port = port == null ? 0 : Integer.parseInt(port);
			} catch (final NumberFormatException e) {
				service.port = 0;
			}
			if (service.type != null && service.host != null && service.port > 0) {
				parsed.add(service);
			}
		}
		return parsed;
	}
}
