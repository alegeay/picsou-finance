"""Parse Groupama Épargne Salariale read-only savings pages.

The portal is an Euro-Information employee-savings site. Its presentation
markup is old and deeply nested, so this module deliberately parses semantic
French headings instead of generated element IDs. Raw HTML never leaves the
sidecar.
"""

from __future__ import annotations

import hashlib
import re
import unicodedata
from decimal import Decimal, InvalidOperation
from typing import Any
from urllib.parse import urljoin, urlsplit

from bs4 import BeautifulSoup, Tag


BASE_URL = "https://www.gestion-epargne-salariale.fr"
SUPPORTED_HOST = "www.gestion-epargne-salariale.fr"
MONEY_ABSOLUTE_TOLERANCE = Decimal("0.05")
MONEY_RELATIVE_TOLERANCE = Decimal("0.001")
ACCOUNT_TABLE_HEADERS = {"nom du support", "nom du profil", "nom du compte"}


class PortfolioFormatError(ValueError):
    """Raised when an upstream snapshot cannot be proven complete."""


def normalized_text(value: str | Tag | None) -> str:
    if isinstance(value, Tag):
        value = value.get_text(" ", strip=True)
    raw = value or ""
    collapsed = " ".join(raw.replace("\xa0", " ").replace("\u202f", " ").split())
    return unicodedata.normalize("NFKD", collapsed).encode("ascii", "ignore").decode().lower()


def decimal_value(raw: Any, field: str = "value") -> Decimal:
    """Parse a required French/English-formatted decimal without false zeroes."""
    if raw is None or isinstance(raw, bool):
        raise PortfolioFormatError(f"Missing {field}")
    value = str(raw).strip().replace("\xa0", " ").replace("\u202f", " ")
    if re.search(r"[$£%]", value):
        raise PortfolioFormatError(f"Invalid {field}")
    negative_parentheses = value.startswith("(") and value.endswith(")")
    if negative_parentheses:
        value = value[1:-1].strip()

    value = re.sub(r"^(?:EUR|€)\s*", "", value, flags=re.IGNORECASE)
    value = re.sub(r"\s*(?:EUR|€)$", "", value, flags=re.IGNORECASE)
    cleaned = value.replace(" ", "").replace("'", "")
    if negative_parentheses:
        cleaned = "-" + cleaned
    if not re.fullmatch(r"[+-]?[0-9][0-9,.-]*", cleaned):
        raise PortfolioFormatError(f"Invalid {field}")
    if "," in cleaned and "." in cleaned:
        if cleaned.rfind(",") > cleaned.rfind("."):
            cleaned = cleaned.replace(".", "").replace(",", ".")
        else:
            cleaned = cleaned.replace(",", "")
    elif cleaned.count(",") > 1:
        head, tail = cleaned.rsplit(",", 1)
        cleaned = head.replace(",", "") + "." + tail
    elif cleaned.count(".") > 1:
        head, tail = cleaned.rsplit(".", 1)
        cleaned = head.replace(".", "") + "." + tail
    else:
        cleaned = cleaned.replace(",", ".")
    try:
        return Decimal(cleaned)
    except InvalidOperation as exc:
        raise PortfolioFormatError(f"Invalid {field}") from exc


def optional_decimal_value(raw: Any, field: str = "value") -> Decimal | None:
    if raw is None or (isinstance(raw, str) and not raw.strip()):
        return None
    return decimal_value(raw, field)


def _money_close(actual: Decimal, expected: Decimal) -> bool:
    tolerance = max(
        MONEY_ABSOLUTE_TOLERANCE,
        abs(expected) * MONEY_RELATIVE_TOLERANCE,
    )
    return abs(actual - expected) <= tolerance


def _account_type(label: str) -> str | None:
    normalized = normalized_text(label)
    if any(marker in normalized for marker in (
        "percol",
        "perco",
        "epargne retraite",
        "plan d'epargne retraite",
        "plan epargne retraite",
    )):
        return "PERCOL"
    if any(marker in normalized for marker in (
        "pee",
        "epargne entreprise",
        "epargne groupe",
        "plan d'epargne entreprise",
        "plan epargne entreprise",
    )):
        return "PEE"
    return None


def _stable_token(prefix: str, *parts: str) -> str:
    seed = "\x1f".join(normalized_text(part) for part in parts if part)
    if not seed:
        raise PortfolioFormatError("Missing stable identifier seed")
    digest = hashlib.sha256(seed.encode("utf-8")).hexdigest()[:20]
    return f"{prefix}_{digest}"


def _direct_table_headers(table: Tag) -> list[Tag]:
    return [
        header
        for header in table.find_all("th")
        if header.find_parent("table") is table
    ]


def _account_label(container: Tag, holdings_table: Tag) -> str:
    for header in _direct_table_headers(container):
        if header is holdings_table or normalized_text(header) in ACCOUNT_TABLE_HEADERS:
            continue
        candidate = header.find("div")
        text = " ".join((candidate or header).stripped_strings)
        if _account_type(text):
            return text.strip()

    # Some portal variants put the plan label one table above the holdings
    # table but do not use a direct TH. Prefer a heading containing a known
    # savings-plan marker over guessing from arbitrary page text.
    for candidate in container.find_all(["div", "h2", "h3", "caption"]):
        text = " ".join(candidate.stripped_strings)
        if _account_type(text):
            return text.strip()
    raise PortfolioFormatError("Missing supported account label")


def _account_balance(container: Tag) -> Decimal:
    for span in container.find_all("span"):
        if "montant total" not in normalized_text(span):
            continue
        sibling = span.find_next_sibling("span")
        if sibling is None:
            sibling = span.find_next()
        if sibling is not None:
            return decimal_value(sibling.get_text(" ", strip=True), "account balance")
    raise PortfolioFormatError("Missing account balance")


def _popup_for(root: BeautifulSoup, cell: Tag) -> Tag | None:
    marker = cell.find("span", id=lambda value: value and "rootSpan" in value)
    if marker is None:
        return None
    marker_id = str(marker.get("id") or "")
    prefix = marker_id.rsplit(":", 1)[0]
    if not prefix:
        return None
    return root.find(
        "div",
        id=lambda value: value and f"dv::s::{prefix}" in value,
    )


def _safe_details_url(raw: str | None) -> str | None:
    if not raw:
        return None
    absolute = urljoin(BASE_URL, raw)
    parsed = urlsplit(absolute)
    if parsed.scheme != "https" or parsed.hostname != SUPPORTED_HOST:
        return None
    return absolute


def _row_label(cell: Tag) -> str:
    link = cell.find("a")
    if link is not None:
        text = link.get_text(" ", strip=True)
        if text:
            return text

    clone = BeautifulSoup(str(cell), "html.parser")
    for generated in clone.find_all(["input", "script", "style"]):
        generated.decompose()
    for marker in clone.find_all("span", id=lambda value: value and "rootSpan" in value):
        marker.decompose()
    text = clone.get_text(" ", strip=True)
    if not text:
        raise PortfolioFormatError("Missing investment label")
    return text


def _absolute_diff(cell: Tag, popup: Tag | None) -> Decimal | None:
    candidates: list[str] = []
    if popup is not None:
        candidates.append(popup.get_text(" ", strip=True))
        candidates.extend(popup.stripped_strings)
    candidates.append(cell.get_text(" ", strip=True))
    candidates.extend(cell.stripped_strings)
    for candidate in candidates:
        normalized = candidate.replace("\xa0", " ").replace("\u202f", " ").strip()
        match = re.search(
            r"(?:^|[\s:])"
            r"(\(?[+-]?\s*\d(?:[\d .,'\u00a0\u202f]*\d)?"
            r"\s*(?:€|EUR)\)?)",
            normalized,
            flags=re.IGNORECASE,
        )
        if match:
            return decimal_value(match.group(1), "investment profit and loss")
    return None


def _position(
    label: str,
    valuation: Decimal,
    diff: Decimal | None,
    details_url: str | None,
    form_param: str | None,
) -> dict[str, Any]:
    return {
        "symbol": _stable_token("GES", label),
        "label": label[:200],
        # The details-page enrichment replaces this neutral valuation unit
        # with the real unit value and derived number of units when exposed.
        "quantity": Decimal("1"),
        "currentPriceEur": valuation,
        "currentValueEur": valuation,
        "pnlEur": diff,
        "_detailsUrl": details_url,
        "_formParam": form_param,
    }


def _breakdown_positions(popup: Tag) -> list[dict[str, Any]]:
    positions: list[dict[str, Any]] = []
    for row in popup.find_all("tr"):
        cells = row.find_all(["td", "th"], recursive=False)
        if len(cells) < 2 or cells[0].name != "td":
            continue
        link = cells[0].find("a")
        label = (link or cells[0]).get_text(" ", strip=True)
        if not label:
            continue
        try:
            valuation = decimal_value(
                cells[1].get_text(" ", strip=True),
                "managed-profile investment valuation",
            )
        except PortfolioFormatError:
            continue
        positions.append(_position(
            label,
            valuation,
            None,
            _safe_details_url(link.get("href") if link else None),
            None,
        ))
    return positions


def _merge_positions(positions: list[dict[str, Any]]) -> list[dict[str, Any]]:
    merged: dict[str, dict[str, Any]] = {}
    for position in positions:
        key = position["symbol"]
        if key not in merged:
            merged[key] = position
            continue
        previous = merged[key]
        previous["currentValueEur"] += position["currentValueEur"]
        previous["currentPriceEur"] = previous["currentValueEur"]
        if previous["pnlEur"] is None or position["pnlEur"] is None:
            previous["pnlEur"] = None
        else:
            previous["pnlEur"] += position["pnlEur"]
        previous["_detailsUrl"] = previous["_detailsUrl"] or position["_detailsUrl"]
        previous["_formParam"] = previous["_formParam"] or position["_formParam"]
    return list(merged.values())


def _parse_positions(root: BeautifulSoup, holdings_table: Tag) -> list[dict[str, Any]]:
    positions: list[dict[str, Any]] = []
    tbody = holdings_table.find("tbody")
    rows = tbody.find_all("tr", recursive=False) if tbody else holdings_table.find_all("tr")
    for row in rows:
        cells = row.find_all("td", recursive=False)
        if len(cells) < 2:
            continue
        row_heading = normalized_text(cells[0])
        if (
            row_heading in ("total", "sous-total")
            or row_heading.startswith("total du ")
            or row_heading.startswith("total de ")
            or row_heading.startswith("total des ")
        ):
            continue
        try:
            valuation = decimal_value(
                cells[1].get_text(" ", strip=True),
                "investment valuation",
            )
        except PortfolioFormatError:
            # Presentation/header/total rows are not holdings.
            continue
        if valuation == 0:
            continue

        allocation_popup = _popup_for(root, cells[0])
        if allocation_popup is not None:
            breakdown = _breakdown_positions(allocation_popup)
            if breakdown:
                breakdown_total = sum(
                    (item["currentValueEur"] for item in breakdown),
                    Decimal("0"),
                )
                if not _money_close(breakdown_total, valuation):
                    raise PortfolioFormatError(
                        "Managed-profile breakdown does not match its valuation"
                    )
                positions.extend(breakdown)
                continue

        label = _row_label(cells[0])
        link = cells[0].find("a")
        form_input = cells[0].find("input", attrs={"name": True})
        diff_cell = cells[2] if len(cells) > 2 else cells[-1]
        positions.append(_position(
            label,
            valuation,
            _absolute_diff(diff_cell, _popup_for(root, diff_cell)),
            _safe_details_url(link.get("href") if link else None),
            str(form_input.get("name")) if form_input else None,
        ))
    return _merge_positions(positions)


def _profile_scope(root: BeautifulSoup) -> str:
    # External ids are member-scoped in Picsou. The employer-space label helps
    # distinguish plans without deriving any stored identifier from the short,
    # brute-forceable customer number displayed elsewhere in the profile.
    company = root.select_one(".profil_entrep")
    return company.get_text(" ", strip=True) if company else ""


def parse_portfolio(html: str) -> list[dict[str, Any]]:
    root = BeautifulSoup(html, "html.parser")
    profile_scope = _profile_scope(root)
    candidates: list[tuple[Tag, Tag]] = []
    seen: set[int] = set()

    for header in root.find_all("th"):
        if normalized_text(header) not in ACCOUNT_TABLE_HEADERS:
            continue
        holdings_table = header.find_parent("table")
        if holdings_table is None:
            continue
        container = holdings_table.find_parent("table") or holdings_table
        identity = id(container)
        if identity in seen:
            continue
        seen.add(identity)
        candidates.append((container, holdings_table))

    accounts: list[dict[str, Any]] = []
    external_ids: set[str] = set()
    for container, holdings_table in candidates:
        try:
            label = _account_label(container, holdings_table)
        except PortfolioFormatError:
            continue
        account_type = _account_type(label)
        if account_type is None:
            # CCB/RSP and any future unsupported plan must not be mislabeled.
            continue
        balance = _account_balance(container)
        positions = _parse_positions(root, holdings_table)
        position_total = sum(
            (position["currentValueEur"] for position in positions),
            Decimal("0"),
        )
        if not _money_close(position_total, balance):
            raise PortfolioFormatError(
                "Investment valuations do not match the account balance"
            )

        external_id = _stable_token(
            "ges",
            profile_scope,
            label,
        )
        if external_id in external_ids:
            raise PortfolioFormatError("Duplicate Groupama savings accounts")
        external_ids.add(external_id)
        accounts.append({
            "externalId": external_id,
            "name": label[:200],
            "type": account_type,
            "balanceEur": balance,
            "positions": positions,
            "snapshotComplete": True,
        })

    if not accounts:
        raise PortfolioFormatError("No supported PEE or PERCOL account found")
    return accounts


def parse_unit_value(html: str) -> Decimal | None:
    root = BeautifulSoup(html, "html.parser")
    for header in root.find_all("th"):
        if "valeur de la part" not in normalized_text(header):
            continue
        row = header.find_parent("tr")
        value_cell = row.find("td") if row else None
        if value_cell is None:
            continue
        try:
            value = decimal_value(value_cell.get_text(" ", strip=True), "unit value")
        except PortfolioFormatError:
            continue
        return value if value > 0 else None
    return None
