# Inactive host tracker-field policy (#112)

`TorrentNetworkPolicy` is an internal metadata-field gate, not an endpoint or network
client. Nothing registers or invokes an active provider. It takes an already bounded,
canonically parsed root dictionary; exact-content validation remains a separate required
gate. The future host must invoke both gates and bind the recorded metadata digest before
any client add. This does not complete #112 or replace the original torrent route.

Administrator configuration must explicitly confirm private tracker admission and supply
1-8 exact HTTPS tracker URLs. URLs may contain private tracker passkeys; exception messages
are deliberately generic and never repeat them. Requests and plugins must not supply or
extend this allowlist. Configuration must use the normal protected secret-handling path,
not a public settings field or logs.

All primary and secondary announce destinations must match the approved strings exactly.
Tier and URL lengths/counts are bounded. Userinfo, fragments, non-HTTPS transports, malformed
URIs and invalid ports are refused. Unknown root/info extensions, webseeds (`url-list`,
`httpseeds`) and DHT bootstrap fields (`nodes`) are refused, not ignored. Only supported
descriptive root fields are accepted with type/size checks. The private info bit is mandatory
but is not proof that the tracker enforces admission.

No DNS lookup, connectivity test, HTTP request, redirect following or IP-range inference
occurs. Exact URL approval cannot prevent DNS rebinding, redirects implemented by a client,
or public routing of a private-looking hostname. Before active use, dedicated client
egress/firewall policy, tracker authorization/revocation, TLS verification, peer admission,
and DNS/redirect behavior require separate reviewed implementation and isolated evidence.
An administrator-approved private-address URL is not automatically trusted by this gate.

Synthetic tests use reserved `.invalid` names and documentation addresses. No test contacts
a tracker or the production server. This gate intentionally rejects UDP trackers and
webseeds; future support requires an explicit policy change and evidence, not relaxation
to make a test pass. Production configuration, sources and `latest` remain unchanged.
