#!/usr/bin/env python3
"""Verify a JSON health response read from standard input."""

import json
import sys


def reject_constant(value):
    raise ValueError("Invalid JSON constant")


def main():
    try:
        response = json.load(sys.stdin, parse_constant=reject_constant)
    except (ValueError, UnicodeError, RecursionError):
        print("Invalid health response: malformed JSON", file=sys.stderr)
        return 2

    if not isinstance(response, dict):
        print("Invalid health response: expected an object", file=sys.stderr)
        return 2
    if "status" not in response:
        print("Invalid health response: missing status", file=sys.stderr)
        return 2
    if not isinstance(response["status"], str):
        print("Invalid health response: status must be a string", file=sys.stderr)
        return 2
    if response["status"] != "UP":
        print("Unhealthy: status is not UP", file=sys.stderr)
        return 1

    print("Healthy: status is UP", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
