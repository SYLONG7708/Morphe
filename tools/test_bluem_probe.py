import io
import unittest
import urllib.request
from bluem_probe import GitHubAPIAuth


class GitHubAPIAuthTest(unittest.TestCase):
    def setUp(self):
        self.handler = GitHubAPIAuth("test-token")

    def test_authenticates_exact_https_api_origin(self):
        for url in ("https://api.github.com/repos/MorpheApp/morphe-patches/releases/latest",
                    "https://api.github.com:443/repos/MorpheApp/MicroG-RE/releases/latest"):
            request = urllib.request.Request(url)
            self.handler.https_request(request)
            self.assertEqual(request.get_header("Authorization"), "Bearer test-token")
            self.assertNotIn("Authorization", request.headers)

    def test_other_origins_and_assets_do_not_receive_token(self):
        for url in ("https://github.com/MorpheApp/MicroG-RE/releases/download/v7.2.1/a.apk",
                    "https://raw.githubusercontent.com/MorpheApp/morphe-patches/main/patches-list.json",
                    "https://release-assets.githubusercontent.com/a.apk",
                    "https://api.github.com.example.com/repos/a",
                    "https://api.github.com:8443/repos/a",
                    "https://someone@api.github.com/repos/a",
                    "http://api.github.com/repos/a"):
            request = urllib.request.Request(url)
            self.handler.https_request(request)
            self.assertIsNone(request.get_header("Authorization"), url)

    def test_redirect_does_not_forward_authorization(self):
        original = urllib.request.Request("https://api.github.com/repos/a/releases/assets/1")
        self.handler.https_request(original)
        redirected = urllib.request.HTTPRedirectHandler().redirect_request(
            original, io.BytesIO(), 302, "Found", {},
            "https://release-assets.githubusercontent.com/a.apk",
        )
        self.handler.https_request(redirected)
        self.assertIsNone(redirected.get_header("Authorization"))

    def test_no_empty_bearer_header(self):
        request = urllib.request.Request("https://api.github.com/repos/a")
        GitHubAPIAuth("").https_request(request)
        self.assertIsNone(request.get_header("Authorization"))


if __name__ == "__main__":
    unittest.main()
