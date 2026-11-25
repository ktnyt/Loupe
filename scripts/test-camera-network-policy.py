#!/usr/bin/env python3
"""Keep the camera client and Android's cleartext exception in agreement."""

from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


SOURCE = Path(__file__).resolve().parents[1] / "app/src/main"
ANDROID = "{http://schemas.android.com/apk/res/android}"
HOST = "192.168.0.10"


class CameraNetworkPolicyTest(unittest.TestCase):
    def test_camera_client_uses_the_fixed_ipv4(self):
        client = (SOURCE / "java/jp/nano/rawgallery/camera/Ep7WifiClient.java").read_text()
        match = re.search(r'public static final String HOST = "([^"]+)";', client)
        self.assertIsNotNone(match)
        self.assertEqual(match[1], HOST)

    def test_cleartext_exception_allows_only_the_literal_camera_host(self):
        application = ET.parse(SOURCE / "AndroidManifest.xml").getroot().find("application")
        self.assertIsNotNone(application)
        self.assertEqual(application.get(ANDROID + "usesCleartextTraffic"), "false")
        self.assertEqual(
            application.get(ANDROID + "networkSecurityConfig"), "@xml/network_security_config"
        )
        config = ET.parse(SOURCE / "res/xml/network_security_config.xml").getroot()
        base = config.find("base-config")
        self.assertIsNotNone(base)
        self.assertEqual(base.get("cleartextTrafficPermitted"), "false")
        allowed = config.findall('.//domain-config[@cleartextTrafficPermitted="true"]')
        self.assertEqual(len(allowed), 1)
        self.assertEqual(
            [(domain.text.strip(), domain.get("includeSubdomains"))
             for domain in allowed[0].findall(".//domain")],
            [(HOST, "false")],
        )


if __name__ == "__main__":
    unittest.main()
