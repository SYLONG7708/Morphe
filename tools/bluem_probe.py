#!/usr/bin/env python3
"""Use scoped GitHub authentication for the Blue M release metadata probe."""
import os
from pathlib import Path
import sys
import urllib.parse
import urllib.request


class GitHubAPIAuth(urllib.request.BaseHandler):
    def __init__(self, token):
        self.token = token

    def https_request(self, request):
        url = urllib.parse.urlsplit(request.full_url)
        if (url.scheme == "https" and url.hostname == "api.github.com"
                and url.port in (None, 443) and url.username is None
                and url.password is None and self.token):
            # RedirectHandler copies ordinary headers. This header must remain
            # on the original API request, never follow an asset/CDN redirect.
            request.add_unredirected_header("Authorization", "Bearer " + self.token)
        return request


def main():
    token = os.environ.get("GH_TOKEN", "")
    if os.environ.get("GITHUB_ACTIONS") == "true" and not token:
        raise RuntimeError("The metadata probe requires its read-only GitHub token")
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "bluem"))
    import cloud
    # The wrapper changes only CI networking. The APK build fingerprint and
    # existing signed channel remain unchanged when upstream versions match.
    urllib.request.install_opener(urllib.request.build_opener(GitHubAPIAuth(token)))
    cloud.probe()


if __name__ == "__main__":
    main()
