"""Command-line tests for the health response verifier (standard library only)."""

import json
from pathlib import Path
import subprocess
import sys
import unittest


SCRIPT = Path(__file__).resolve().with_name("verify-health.py")


class VerifyHealthTest(unittest.TestCase):
    def check_response(self, payload, exit_code, diagnostic):
        result = subprocess.run(
            [sys.executable, str(SCRIPT)],
            input=payload,
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(result.returncode, exit_code, result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertEqual(result.stderr, diagnostic + "\n")

    def test_up(self):
        self.check_response(
            '{"status":"UP","secret":"do-not-echo"}', 0, "Healthy: status is UP"
        )

    def test_non_up(self):
        for status in ("DOWN", "up", "UP ", "", "do-not-echo"):
            with self.subTest(status=status):
                self.check_response(
                    json.dumps({"status": status}), 1, "Unhealthy: status is not UP"
                )

    def test_malformed_json(self):
        for payload in ('{"secret":"do-not-echo",', "", '{} {}',
                        '{"status":"UP","value":NaN}'):
            with self.subTest(payload=payload):
                self.check_response(
                    payload, 2, "Invalid health response: malformed JSON"
                )

    def test_missing_status(self):
        self.check_response(
            '{"nested":{"status":"UP"},"secret":"do-not-echo"}',
            2, "Invalid health response: missing status",
        )

    def test_non_string_status(self):
        for status in (None, True, 1, [], {}):
            with self.subTest(status=status):
                self.check_response(
                    json.dumps({"status": status}),
                    2, "Invalid health response: status must be a string",
                )

    def test_non_object_json(self):
        for value in ([], "do-not-echo", 42, True, None):
            with self.subTest(value=value):
                self.check_response(
                    json.dumps(value), 2, "Invalid health response: expected an object"
                )


if __name__ == "__main__":
    unittest.main()
