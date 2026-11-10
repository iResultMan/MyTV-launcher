import unittest

import budget

MEMINFO = """** MEMINFO in pid 2460 [com.gathin.tvhome] **
        TOTAL    92047    64376    17204     1219    92047    78600    59458     9592
           TOTAL PSS:    92047            TOTAL RSS:   142800       TOTAL SWAP PSS:     1219
"""


class BudgetTest(unittest.TestCase):
    def test_pss_is_read_from_the_summary_line(self):
        self.assertEqual(92047, budget.pss_kb(MEMINFO))
        self.assertEqual(92047, budget.pss_kb(MEMINFO.splitlines()[1]))
        self.assertIsNone(budget.pss_kb("no process"))

    def test_cpu_ticks_survive_a_command_name_with_spaces_and_brackets(self):
        stat = "2460 (tv home (x)) S 1 2 3 4 5 6 7 8 9 10 120 30 0 0 20 0 1 0 100 200 300"
        self.assertEqual(150, budget.cpu_ticks(stat))

    def test_cpu_percent_is_a_share_of_one_core(self):
        a = "1 (x) S 1 1 1 1 1 1 1 1 1 1 100 50 0 0 20 0 1 0 1 1 1"
        b = "1 (x) S 1 1 1 1 1 1 1 1 1 1 103 50 0 0 20 0 1 0 1 1 1"
        self.assertAlmostEqual(1.0, budget.cpu_percent(a, b, 3.0))

    def test_budgets(self):
        self.assertEqual([], budget.check(76, 0.4, 611421, 80, 1.0))
        bad = budget.check(92, 2.5, 1048576, 80, 1.0)
        self.assertEqual(3, len(bad))
        self.assertEqual([], budget.check(None, None, None, 80, 1.0))


if __name__ == "__main__":
    unittest.main()
