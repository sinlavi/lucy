import unittest

from scripts.process_audio import normalize_text, rank_candidates


class AudioMatchingTests(unittest.TestCase):
    def setUp(self):
        self.track = {
            "trackName": "Midnight City",
            "artistName": "M83",
            "collectionName": "Hurry Up, We're Dreaming",
            "trackTimeMillis": 244000,
        }
        self.allowed_channels = {"UCauthorized123"}

    def test_best_title_artist_album_and_duration_match_ranks_first(self):
        candidates = [
            {
                "id": "abcdefghijk",
                "title": "Midnight City (Official Audio)",
                "artists": ["M83"],
                "album": "Hurry Up, We're Dreaming",
                "duration": 244,
                "channel_id": "UCauthorized123",
            },
            {
                "id": "lmnopqrstuv",
                "title": "Midnight City Live",
                "artists": ["Different Artist"],
                "album": "Live Sessions",
                "duration": 360,
                "channel_id": "UCauthorized123",
            },
        ]

        ranked = rank_candidates(self.track, candidates, self.allowed_channels)

        self.assertEqual(ranked[0]["candidate"]["id"], "abcdefghijk")
        self.assertGreater(ranked[0]["score"], ranked[1]["score"])

    def test_candidates_outside_authorized_channel_allowlist_are_ignored(self):
        candidate = {
            "id": "abcdefghijk",
            "title": "Midnight City",
            "channel_id": "UCnotauthorized",
        }

        self.assertEqual(rank_candidates(self.track, [candidate], self.allowed_channels), [])

    def test_exact_title_with_wrong_artist_is_not_accepted(self):
        candidate = {
            "id": "abcdefghijk",
            "title": "Midnight City",
            "artists": ["Different Artist"],
            "album": "Hurry Up, We're Dreaming",
            "duration": 244,
            "channel_id": "UCauthorized123",
        }

        ranked = rank_candidates(self.track, [candidate], self.allowed_channels)

        self.assertLess(ranked[0]["score"], 0.68)

    def test_normalization_handles_punctuation_and_arabic_letter_variants(self):
        self.assertEqual(normalize_text("كِتاب (Official Audio)!"), "کتاب")

    def test_external_candidate_url_is_rejected(self):
        candidate = {
            "id": "abcdefghijk",
            "title": "Midnight City",
            "channel_id": "UCauthorized123",
            "webpage_url": "https://example.com/track",
        }

        self.assertEqual(rank_candidates(self.track, [candidate], self.allowed_channels), [])


if __name__ == "__main__":
    unittest.main()