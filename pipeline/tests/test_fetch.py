import os, sys, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import fetch

A44 = b"""<?xml version="1.0" encoding="UTF-8"?>
<Publication_MarketDocument xmlns="urn:iec62325.351:tc57wg16:451-3:publicationdocument:7:3">
 <TimeSeries><curveType>A03</curveType>
  <Period><timeInterval><start>2026-11-16T23:00Z</start><end>2026-11-17T00:00Z</end></timeInterval>
   <resolution>PT15M</resolution>
   <Point><position>1</position><price.amount>90.5</price.amount></Point>
   <Point><position>3</position><price.amount>80</price.amount></Point>
  </Period></TimeSeries>
</Publication_MarketDocument>"""

A75 = b"""<GL_MarketDocument xmlns="urn:iec62325.351:tc57wg16:451-6:generationloaddocument:3:0">
 <TimeSeries><inBiddingZone_Domain.mRID codingScheme="A01">10YCA-BULGARIA-R</inBiddingZone_Domain.mRID>
  <curveType>A01</curveType><MktPSRType><psrType>B14</psrType></MktPSRType>
  <Period><timeInterval><start>2026-11-17T00:00Z</start><end>2026-11-17T02:00Z</end></timeInterval>
   <resolution>PT60M</resolution>
   <Point><position>1</position><quantity>2040</quantity></Point>
   <Point><position>2</position><quantity>2038</quantity></Point>
  </Period></TimeSeries></GL_MarketDocument>"""

ACK = b"""<Acknowledgement_MarketDocument xmlns="urn:iec62325.351:tc57wg16:451-1:acknowledgementdocument:7:0">
 <Reason><code>999</code><text>No matching data found for Data item</text></Reason></Acknowledgement_MarketDocument>"""


class T(unittest.TestCase):
    def test_a03_fills_gaps(self):
        s = fetch.parse_entsoe(A44)
        self.assertEqual(len(s[0]["points"]), 4)       # 4 quarter-hours
        self.assertEqual(s[0]["points"][1][1], 90.5)   # position 2 repeats position 1
        self.assertEqual(s[0]["points"][3][1], 80.0)
        self.assertEqual(s[0]["resolution"], "PT15M")

    def test_generation_psr(self):
        s = fetch.parse_entsoe(A75)
        self.assertEqual(s[0]["psr"], "B14")
        self.assertEqual(s[0]["in"], "10YCA-BULGARIA-R")
        self.assertEqual(fetch.PSR[s[0]["psr"]], "Nuclear")
        self.assertEqual(s[0]["points"][1][0].hour, 1)

    def test_ack_is_no_data(self):
        with self.assertRaises(RuntimeError) as c:
            fetch.parse_entsoe(ACK)
        self.assertIn("No matching data", str(c.exception))

    def test_ec_v2(self):
        o = {"unit": "GW", "series": [{"id": "ro", "name": "Romania"}],
             "data": [{"timestamp": "2026-11-17T00:00:00+01:00", "values": {"ro": 0.5}},
                      {"timestamp": "2026-11-17T00:15:00+01:00", "values": {"ro": None}}]}
        times, cols = fetch.ec_series(o)
        self.assertEqual(cols["Romania"], [500.0, None])

    def test_classify(self):
        c = fetch.classify("Pilot sodium-ion battery plant in Bulgaria", "")
        self.assertIn("Батерии", c["categories"])
        self.assertEqual(c["bg_relevance"], "висока")
        self.assertEqual(c["maturity"], "Пилотна демонстрация")

    def test_rss(self):
        rss = b"<rss><channel><item><title>A &amp; B</title><link>https://x/1</link><pubDate>Mon, 16 Nov 2026 10:00:00 GMT</pubDate><description>&lt;p&gt;Hi&lt;/p&gt;</description></item></channel></rss>"
        it = fetch.parse_feed(rss)[0]
        self.assertEqual(it["title"], "A & B"); self.assertEqual(it["summary"], "Hi")
        self.assertIsNotNone(fetch.parse_date(it["date"]))

    def test_daily_avgs_sofia_day(self):
        pts = [["2026-11-16T22:00Z", 10.0]] * 24   # 00:00 Sofia on the 17th
        self.assertEqual(fetch.daily_avgs(pts)[0][0], "2026-11-17")

    def _quarters(self, start_utc, hours):
        import datetime as dt
        t0 = dt.datetime.fromisoformat(start_utc)
        return [[fetch.iso(t0 + dt.timedelta(minutes=15 * i)), 1.0] for i in range(hours * 4)]

    def test_dst_autumn_25h_day(self):
        # 25.10.2026: Sofia goes from EEST (+3) to EET (+2) -> the local day has 25 hours.
        pts = self._quarters("2026-10-24T21:00+00:00", 25)
        days = fetch.daily_avgs(pts)
        self.assertEqual([d for d, _ in days], ["2026-10-25"])
        self.assertEqual(fetch.day_stats(pts, "2026-10-25")["n"], 100)

    def test_dst_spring_23h_day(self):
        # 29.03.2026: EET (+2) -> EEST (+3) -> the local day has 23 hours.
        pts = self._quarters("2026-03-28T22:00+00:00", 23)
        self.assertEqual(fetch.day_stats(pts, "2026-03-29")["n"], 92)
        self.assertIsNone(fetch.day_stats(pts, "2026-03-30"))

    def test_no_energy_charts_when_entsoe_configured(self):
        old = fetch.TOKEN
        try:
            fetch.TOKEN = "x"
            self.assertFalse(fetch.fallback_allowed())
            fetch.TOKEN = ""
            self.assertTrue(fetch.fallback_allowed())
        finally:
            fetch.TOKEN = old

    def test_hourly_avgs(self):
        pts = [["2026-11-17T10:00Z", 10.0], ["2026-11-17T10:15Z", 20.0], ["2026-11-17T11:00Z", 5.0]]
        self.assertEqual(fetch.hourly_avgs(pts), [["2026-11-17T10Z", 15.0], ["2026-11-17T11Z", 5.0]])


if __name__ == "__main__":
    unittest.main()
