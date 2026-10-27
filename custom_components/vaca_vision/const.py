"""Overlay integration identity, independent of VACA voice satellites."""

DOMAIN = "vaca_vision"
NAME = "VACA_Vision"
VERSION = "0.6.0"
MIN_APP_VERSION = "0.1.0"
PROTOCOL = "vaca-vision"
PROTOCOL_VERSION = 1
SERVICE_TYPE = "_vaca-vision._tcp.local."
DEFAULT_PORT = 10900
CONF_REPOSITORY = "app_updates_repository"
DEFAULT_REPOSITORY = ""

ROLE_OVERLAY = "overlay_display_only"
ROLE_STANDARD = "standard_display_only"
ROLE_DOT = "standard_dot_sendspin"
ROLE_CROWN = "standard_assist_sendspin"
ROLE_OVERLAY_DOT = "overlay_echo_dot"
ROLE_UPSTREAM = "standard_upstream_eda"
DOT_ROLES = {ROLE_DOT, ROLE_OVERLAY_DOT}
OVERLAY_ROLES = {ROLE_OVERLAY, ROLE_OVERLAY_DOT}
ROLES = {
    ROLE_UPSTREAM: "Echo Show 8 with Echo Dot Assist",
    ROLE_OVERLAY: "Overlay Display Only",
    ROLE_STANDARD: "Standard Display Only",
    ROLE_DOT: "Display and Dot with sendspin",
    ROLE_OVERLAY_DOT: "Overlay Display and Dot with sendspin",
    ROLE_CROWN: "Standard Display with assist and sendspin",
}
DEVELOPMENT_ROLES = {}
