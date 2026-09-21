#!/usr/bin/env python3
"""Generate a settlement file with a known number of deliberately wrong rows.

This is the second implementation. It computes MDR from the same published
rules as in.reconpilot.mdr.MdrCalculator, but from the rules -- not from that
code. That independence is the entire point: a generator that shared the
engine's arithmetic would agree with it about everything, including its
mistakes, and would prove nothing.

It has already earned this. An earlier version truncated where the Java rounds
HALF_UP, and one paise of disagreement surfaced as 8,174 false breaks in a
single million-row run. A test written by the author of the engine would not
have found that.

    ./generate-settlements.py --rows 20000 --breaks 150 --out settlements.csv

Prints a JSON summary of what it planted. Assert on the TOTAL, not on the
per-type split: the engine labels a row by what was charged, this generator by
what was correct, and the two disagree on one boundary case -- a row whose
correct MDR sits below the Rs 300 cap but whose (wrong) charge lands above it
is CAP_BREACHED to the engine and OVERCHARGED here. Both labels are defensible
and neither is a miss. The count of wrong rows found is exact either way, and
that is the claim worth making.
"""
import argparse, json, random, sys
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP

THRESHOLD_PAISE = 200_000      # Rs 2,000, exclusive
CAP_PAISE       = 30_000       # Rs 300 per transaction
INDUSTRY_FLAT   = 500          # Rs 5 flat
RATE = {"STANDARD": Decimal("0.004"), "CAPITAL_MARKETS": Decimal("0.0002")}

# EDUCATION and UPI_CREDIT_LINE are deliberately absent. The engine raises
# UnspecifiedRuleException for both, because the primary source states no rate
# for one and hands the other to card rules (FAQ Q42, Q36). Generating them
# would be generating rows nobody can say the correct answer for.
RAILS      = ["UPI_QR", "UPI_INTENT", "UPI_AUTOPAY"]
CATEGORIES = ["STANDARD", "STANDARD", "STANDARD", "CAPITAL_MARKETS", "INDUSTRY_PROGRAM"]
TXN_TYPES  = ["P2M", "P2M", "P2M", "P2M", "P2P", "P2PM"]


def correct_mdr(amount, txn_type, rail, category):
    """The order of these checks is part of the rule, not an implementation
    detail. P2PM must be tested BEFORE the amount threshold: a micro-merchant
    is exempt at any amount, and checking the amount first would quietly charge
    them on large transactions."""
    if txn_type == "P2P":
        return 0
    if rail == "UPI_AUTOPAY":
        return 0
    if txn_type == "P2PM":
        return 0
    if amount <= THRESHOLD_PAISE:
        return 0
    if category == "INDUSTRY_PROGRAM":
        return INDUSTRY_FLAT
    # ROUND_HALF_UP explicitly. Python's built-in round() is banker's rounding,
    # which disagrees with the engine on every exact half -- and a one-paise
    # disagreement is indistinguishable from a real overcharge.
    raw = (Decimal(amount) * RATE[category]).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
    return min(int(raw), CAP_PAISE)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rows", type=int, default=20_000)
    ap.add_argument("--breaks", type=int, default=150)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, default=20261015)
    a = ap.parse_args()

    if a.breaks > a.rows:
        sys.exit("--breaks cannot exceed --rows")

    rng = random.Random(a.seed)          # seeded: the same run twice is the same file
    start = datetime(2026, 10, 15, tzinfo=timezone.utc)

    # Decide up front which rows are wrong, so the count planted is exact
    # rather than probabilistic. "About 150 breaks" is not something a test can
    # assert on.
    broken = set(rng.sample(range(a.rows), a.breaks))
    planted = {"CHARGED_WHEN_EXEMPT": 0, "CAP_BREACHED": 0,
               "OVERCHARGED": 0, "UNDERCHARGED": 0}

    with open(a.out, "w", newline="") as f:
        f.write("txn_id,merchant_vpa,amount_paise,txn_type,rail,"
                "payee_category,charged_mdr_paise,occurred_at\n")

        for i in range(a.rows):
            txn_type = rng.choice(TXN_TYPES)
            rail     = rng.choice(RAILS)
            category = rng.choice(CATEGORIES)
            # Weighted low, like real traffic, but with enough large values to
            # exercise the Rs 300 cap.
            amount   = rng.choice([
                rng.randint(1_000, 200_000),        # at or below the threshold
                rng.randint(200_001, 7_500_000),    # ordinary
                rng.randint(7_500_000, 20_000_000), # above where the cap binds
            ])

            correct = correct_mdr(amount, txn_type, rail, category)
            charged = correct

            if i in broken:
                if correct == 0:
                    # Charged a merchant who owes nothing. The worst kind:
                    # invisible unless someone recomputes it.
                    charged = rng.randint(100, 5_000)
                    planted["CHARGED_WHEN_EXEMPT"] += 1
                elif correct == CAP_PAISE:
                    charged = CAP_PAISE + rng.randint(1, 10_000)
                    planted["CAP_BREACHED"] += 1
                elif rng.random() < 0.6:
                    charged = correct + rng.randint(1, 2_000)
                    planted["OVERCHARGED"] += 1
                else:
                    charged = max(0, correct - rng.randint(1, min(correct, 2_000)))
                    if charged == correct:            # nothing to subtract
                        charged = correct + 1
                        planted["OVERCHARGED"] += 1
                    else:
                        planted["UNDERCHARGED"] += 1

            occurred = start + timedelta(seconds=i * 7 % 2_400_000)
            f.write(f"TXN{i:09d},merchant{i % 800:04d}@upi,{amount},{txn_type},"
                    f"{rail},{category},{charged},"
                    f"{occurred.strftime('%Y-%m-%dT%H:%M:%SZ')}\n")

    print(json.dumps({"rows": a.rows, "breaks": a.breaks, "planted": planted}, indent=2))


if __name__ == "__main__":
    main()
