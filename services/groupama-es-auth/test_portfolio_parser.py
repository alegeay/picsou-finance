import unittest
from decimal import Decimal

from portfolio_parser import (
    PortfolioFormatError,
    decimal_value,
    parse_portfolio,
    parse_unit_value,
)


def page(*accounts: str, customer_number: str = "12345678") -> str:
    return f"""
    <html><body>
      <div id="ei_tpl_fullSite">
        <div class="ei_tpl_profil_content"><p>Client {customer_number}</p></div>
        <p class="profil_entrep">ACME France</p>
        {''.join(accounts)}
      </div>
    </body></html>
    """


def account(label: str, balance: str, rows: str, account_id: str = "") -> str:
    return f"""
    <table id="{account_id}">
      <tbody>
        <tr><th><div>{label}</div></th></tr>
        <tr><td>
          <span>Montant total</span><span>{balance}</span>
          <table>
            <thead><tr><th>Nom du support</th><th>Montant</th><th>+/- value</th></tr></thead>
            <tbody>{rows}</tbody>
          </table>
        </td></tr>
      </tbody>
    </table>
    """


class DecimalParserTest(unittest.TestCase):
    def test_parses_french_and_english_money(self):
        self.assertEqual(decimal_value("1\u202f234,56 €"), Decimal("1234.56"))
        self.assertEqual(decimal_value("EUR -42.50"), Decimal("-42.50"))
        self.assertEqual(decimal_value("(12,34 €)"), Decimal("-12.34"))

    def test_rejects_malformed_required_value(self):
        with self.assertRaises(PortfolioFormatError):
            decimal_value("1e3")

    def test_rejects_non_eur_and_percentage_values(self):
        for raw in ("$42.00", "42,00 £", "42,00 %"):
            with self.subTest(raw=raw), self.assertRaises(PortfolioFormatError):
                decimal_value(raw)


class PortfolioParserTest(unittest.TestCase):
    def test_parses_pee_and_absolute_profit_from_popup(self):
        rows = """
          <tr>
            <td><a href="/groupama-es/espace-client/fr/epargnants/supports/fiche-du-support.html?id=1">Groupama Sélection PME</a></td>
            <td>1 250,50 €</td>
            <td><span id="diff:one:rootSpan">+4,0 %</span></td>
          </tr>
        """
        html = page(
            account("Plan d'épargne entreprise (PEE)", "1 250,50 €", rows, "pee-main"),
        ) + """
          <div id="dv::s::diff:one"><span>Plus-value : +50,25 EUR</span></div>
        """

        parsed = parse_portfolio(html)

        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0]["type"], "PEE")
        self.assertEqual(parsed[0]["balanceEur"], Decimal("1250.50"))
        self.assertEqual(parsed[0]["positions"][0]["pnlEur"], Decimal("50.25"))
        self.assertTrue(parsed[0]["externalId"].startswith("ges_"))
        self.assertTrue(parsed[0]["positions"][0]["symbol"].startswith("GES_"))

    def test_parses_absolute_profit_with_an_english_decimal_point(self):
        rows = """
          <tr>
            <td>Groupama Actions</td>
            <td>1,250.50 EUR</td>
            <td><span id="diff:english:rootSpan">+4.0 %</span></td>
          </tr>
        """
        html = page(
            account("Plan d'épargne entreprise", "1,250.50 EUR", rows),
        ) + """
          <div id="dv::s::diff:english">
            <span>Plus-value: +50.25 EUR</span>
          </div>
        """

        parsed = parse_portfolio(html)

        self.assertEqual(
            parsed[0]["positions"][0]["pnlEur"],
            Decimal("50.25"),
        )

    def test_parses_one_currency_fragment_from_a_decorated_valuation_cell(self):
        rows = """
          <tr>
            <td>Support entreprise</td>
            <td>
              <a href="#">
                <span>Montant</span>
                <span>200,00 €</span>
                <span>Parts disponibles</span>
                <span>42</span>
              </a>
            </td>
            <td><span id="diff:decorated:rootSpan">+2,0 %</span></td>
          </tr>
        """

        parsed = parse_portfolio(page(
            account("Épargne entreprise", "200,00 €", rows),
        ))

        self.assertEqual(
            parsed[0]["positions"][0]["currentValueEur"],
            Decimal("200.00"),
        )

    def test_rejects_ambiguous_currency_fragments_in_a_valuation_cell(self):
        rows = """
          <tr>
            <td>Support entreprise</td>
            <td><span>150,00 €</span><span>50,00 €</span></td>
            <td><span id="diff:ambiguous:rootSpan">+2,0 %</span></td>
          </tr>
        """

        with self.assertRaises(PortfolioFormatError):
            parse_portfolio(page(
                account("Épargne entreprise", "200,00 €", rows),
            ))

    def test_expands_a_percol_managed_profile(self):
        rows = """
          <tr>
            <td><span id="allocation:pilot:rootSpan">Profil piloté</span></td>
            <td>3 000,00 €</td>
            <td>+3,2 %</td>
          </tr>
        """
        popup = """
          <div id="dv::s::allocation:pilot">
            <table>
              <tr><th colspan="2">Répartition</th></tr>
              <tr><th>Support</th><th>Montant</th></tr>
              <tr><td><a href="/groupama-es/espace-client/fr/epargnants/supports/fiche-du-support.html?id=a">Fonds prudent</a></td><td>1 000,00 €</td></tr>
              <tr><td><a href="/groupama-es/espace-client/fr/epargnants/supports/fiche-du-support.html?id=b">Fonds dynamique</a></td><td>2 000,00 €</td></tr>
            </table>
          </div>
        """
        parsed = parse_portfolio(
            page(account("Épargne retraite - PERCOL piloté", "3 000,00 €", rows))
            + popup
        )

        self.assertEqual(parsed[0]["type"], "PERCOL")
        self.assertEqual(len(parsed[0]["positions"]), 2)
        self.assertEqual(
            sum(item["currentValueEur"] for item in parsed[0]["positions"]),
            Decimal("3000.00"),
        )

    def test_normalizes_per_collectif_and_peg_labels(self):
        per_collectif = account(
            "PER Collectif",
            "100,00 €",
            "<tr><td>Support retraite</td><td>100,00 €</td><td></td></tr>",
        )
        peg = account(
            "PEG",
            "200,00 €",
            "<tr><td>Support entreprise</td><td>200,00 €</td><td></td></tr>",
        )

        parsed = parse_portfolio(page(per_collectif, peg))

        self.assertEqual(
            [item["type"] for item in parsed],
            ["PERCOL", "PEE"],
        )

    def test_parses_pee_and_percol_together(self):
        parsed = parse_portfolio(page(
            account(
                "Plan d'épargne entreprise (PEE)",
                "100,00 €",
                "<tr><td>Support entreprise</td><td>100,00 €</td><td></td></tr>",
            ),
            account(
                "Épargne retraite - PERCOL",
                "200,00 €",
                "<tr><td>Support retraite</td><td>200,00 €</td><td></td></tr>",
            ),
        ))

        self.assertEqual([item["type"] for item in parsed], ["PEE", "PERCOL"])
        self.assertEqual([item["balanceEur"] for item in parsed], [Decimal("100"), Decimal("200")])

    def test_same_support_label_with_distinct_portal_links_is_not_merged(self):
        rows = """
          <tr>
            <td><a href="/groupama-es/espace-client/fr/epargnants/supports/fiche-du-support.html?id=one">Fonds équilibré</a></td>
            <td>100,00 €</td><td></td>
          </tr>
          <tr>
            <td><a href="/groupama-es/espace-client/fr/epargnants/supports/fiche-du-support.html?id=two">Fonds équilibré</a></td>
            <td>200,00 €</td><td></td>
          </tr>
        """

        parsed = parse_portfolio(page(account("Épargne entreprise", "300,00 €", rows)))

        self.assertEqual(len(parsed[0]["positions"]), 2)
        self.assertEqual(
            [item["currentValueEur"] for item in parsed[0]["positions"]],
            [Decimal("100"), Decimal("200")],
        )

    def test_skips_an_unsupported_blocked_current_account(self):
        ccb = account(
            "Compte courant bloqué",
            "100,00 €",
            "<tr><td>Participation</td><td>100,00 €</td><td></td></tr>",
        )
        pee = account(
            "Épargne entreprise",
            "200,00 €",
            "<tr><td>Support solidaire</td><td>200,00 €</td><td></td></tr>",
        )

        parsed = parse_portfolio(page(ccb, pee))

        self.assertEqual([item["type"] for item in parsed], ["PEE"])

    def test_rejects_an_incomplete_snapshot(self):
        html = page(account(
            "Épargne entreprise",
            "500,00 €",
            "<tr><td>Support incomplet</td><td>400,00 €</td><td></td></tr>",
        ))

        with self.assertRaises(PortfolioFormatError):
            parse_portfolio(html)

    def test_ignores_a_numeric_account_total_row(self):
        rows = """
          <tr><td>TotalEnergies Actionnariat</td><td>200,00 €</td><td></td></tr>
          <tr><td>Total du compte</td><td>200,00 €</td><td></td></tr>
        """

        parsed = parse_portfolio(page(
            account("Épargne entreprise", "200,00 €", rows),
        ))

        self.assertEqual(len(parsed[0]["positions"]), 1)
        self.assertEqual(
            parsed[0]["positions"][0]["label"],
            "TotalEnergies Actionnariat",
        )

    def test_ignores_unmarked_numeric_rows_when_holdings_have_portal_markers(self):
        rows = """
          <tr>
            <td>Support entreprise</td>
            <td>200,00 €</td>
            <td><span id="diff:marked:rootSpan">+2,0 %</span></td>
          </tr>
          <tr>
            <td>Versements pris en compte</td>
            <td>50,00 €</td>
            <td></td>
          </tr>
        """

        parsed = parse_portfolio(page(
            account("Épargne entreprise", "200,00 €", rows),
        ))

        self.assertEqual(len(parsed[0]["positions"]), 1)
        self.assertEqual(
            parsed[0]["positions"][0]["label"],
            "Support entreprise",
        )

    def test_account_identity_is_stable_for_the_same_profile_and_plan(self):
        first_html = page(
            account(
                "Épargne entreprise",
                "10,00 €",
                "<tr><td>Support</td><td>10,00 €</td><td></td></tr>",
                "generated-session-a",
            ),
            customer_number="12345678",
        )
        second_html = page(
            account(
                "Épargne entreprise",
                "10,00 €",
                "<tr><td>Support</td><td>10,00 €</td><td></td></tr>",
                "generated-session-b",
            ),
            customer_number="87654321",
        )

        first = parse_portfolio(first_html)[0]["externalId"]
        second = parse_portfolio(second_html)[0]["externalId"]

        self.assertEqual(first, second)

    def test_reads_unit_value_from_investment_details(self):
        html = """
          <table><tr>
            <th>Valeur de la part au 25/07/2026</th>
            <td><em>42,1250 €</em></td>
          </tr></table>
        """
        self.assertEqual(parse_unit_value(html), Decimal("42.1250"))


if __name__ == "__main__":
    unittest.main()
