#!/usr/bin/env bash
# End-to-end demo against a running OpenSimilarity server. Requires curl and jq.
# Creates a new collection each run and exits non-zero if any expected result is missing.
#   usage: scripts/demo.sh [base-url]
set -euo pipefail

BASE="${1:-http://localhost:8080}"
SAMPLES="$(cd "$(dirname "$0")/../samples" && pwd)"
failures=0

expect() { # description, jq filter over $report, must evaluate to true
    if jq -e "$2" <<<"$3" >/dev/null; then
        echo "  PASS  $1"
    else
        echo "  FAIL  $1"
        failures=$((failures + 1))
    fi
}

upload() { # file [consent]
    curl -fsS -X POST "$BASE/api/collections/$COLLECTION/documents" -H 'X-Actor: demo' \
        -F "file=@$SAMPLES/$1" -F "consentToIndex=${2:-true}" | jq -r .id
}

check() { # document id [json body]
    curl -fsS -X POST "$BASE/api/documents/$1/checks" -H 'X-Actor: demo' \
        -H 'Content-Type: application/json' -d "${2:-{\}}"
}

summarize() {
    jq '{overlapPercent: .summary.overlapPercent, paraphraseLeadPercent: .summary.paraphraseLeadPercent,
         matches: [.matches[] | {method, source: .sourceTitle, matchedWords, confidence,
                                 querySpan: [.query.charStart, .query.charEnd], sourceSpan: [.source.charStart, .source.charEnd]}],
         excluded: [.excludedMatches[] | {excludedBecause, matchedWords}]}' <<<"$1"
}

echo "== Health"
curl -fsS "$BASE/api/health" | jq -c .

echo "== Create collection and upload samples"
COLLECTION=$(curl -fsS -X POST "$BASE/api/collections" -H 'X-Actor: demo' -H 'Content-Type: application/json' \
    -d '{"name":"Demo: CVE 401 final year projects","department":"Civil Engineering","indexPolicy":"COURSE_ONLY","retentionDays":365}' | jq -r .id)
echo "collection $COLLECTION"
SOURCE=$(upload source_paper.pdf)
VERBATIM=$(upload student_verbatim.txt)
VERBATIM_PDF=$(upload student_verbatim.pdf false)
VERBATIM_DOCX=$(upload student_verbatim.docx false)
PARAPHRASE=$(upload student_paraphrase_light.txt)
HEAVY=$(upload student_paraphrase_heavy.txt)
BIBLIOGRAPHY=$(upload student_bibliography_only.txt)
QUOTED=$(upload student_quoted.txt)
SAME_TOPIC=$(upload control_same_topic.txt)
UNRELATED=$(upload control_unrelated.txt)
HOMOGLYPH=$(upload student_homoglyph.txt false)
HIDDEN=$(upload student_hidden_text.docx false)
GROUPED=$(upload student_match_groups.txt false)
TEMPLATE_A=$(upload student_template_a.txt)
TEMPLATE_B=$(upload student_template_b.txt)

PLANTED='A hostel block housing four hundred students'
PLANTED_END='without any generator running at all'

echo "== 1. Verbatim paragraph: student_verbatim.txt vs source_paper.pdf"
report=$(check "$VERBATIM")
summarize "$report"
expect "exactly one reported match, from the source paper" \
    "[.matches[] | select(.sourceDocumentId == \"$SOURCE\")] | length == 1" "$report"
expect "tagged FINGERPRINT with confidence 1" \
    "[.matches[] | select(.sourceDocumentId == \"$SOURCE\")][0] | .method == \"FINGERPRINT\" and .confidence == 1" "$report"
expect "query span covers the planted paragraph" \
    "[.matches[] | select(.sourceDocumentId == \"$SOURCE\")][0].query.text | (startswith(\"$PLANTED\") and endswith(\"$PLANTED_END\"))" "$report"
expect "source span covers the planted paragraph" \
    "[.matches[] | select(.sourceDocumentId == \"$SOURCE\")][0].source.text | (startswith(\"$PLANTED\") and endswith(\"$PLANTED_END\"))" "$report"
expect "overall overlap is well above zero" '.summary.overlapPercent > 20' "$report"

echo "== 1b. Query spans point at the real text"
text=$(curl -fsS "$BASE/api/documents/$VERBATIM/text")
start=$(jq "[.matches[] | select(.sourceDocumentId == \"$SOURCE\")][0].query.charStart" <<<"$report")
end=$(jq "[.matches[] | select(.sourceDocumentId == \"$SOURCE\")][0].query.charEnd" <<<"$report")
slice=$(jq -rn --arg t "$text" --argjson s "$start" --argjson e "$end" '$t[$s:$e]')
expect "text[charStart:charEnd] equals the reported span" \
    "[.matches[] | select(.sourceDocumentId == \"$SOURCE\")][0].query.text == $(jq -Rs . <<<"$slice" | jq 'rtrimstr("\n")')" "$report"

echo "== 1c. Same essay as PDF and DOCX finds the same paragraph"
for doc in "$VERBATIM_PDF" "$VERBATIM_DOCX"; do
    r=$(check "$doc")
    expect "match found" "[.matches[] | select(.sourceDocumentId == \"$SOURCE\" and .method == \"FINGERPRINT\")][0].query.text | startswith(\"$PLANTED\")" "$r"
done

echo "== 2. Bibliography-only overlap"
report_on=$(check "$BIBLIOGRAPHY")
report_off=$(check "$BIBLIOGRAPHY" '{"excludeBibliography":false}')
summarize "$report_on"
expect "filter on: no reported match" '.summary.overlapPercent == 0 and (.matches | length) == 0' "$report_on"
expect "filter on: the removed overlap stays visible, with its reason" \
    '[.excludedMatches[] | select(.excludedBecause == "BIBLIOGRAPHY")] | length >= 1' "$report_on"
expect "filter off: the same overlap is reported" '.summary.overlapPercent > 20' "$report_off"

echo "== 3. Quoted passage"
report_on=$(check "$QUOTED")
report_off=$(check "$QUOTED" '{"excludeQuotes":false}')
summarize "$report_on"
expect "filter on: quotation not counted" '.summary.overlapPercent == 0' "$report_on"
expect "filter on: the removed overlap stays visible, with its reason" \
    "[.excludedMatches[] | select(.excludedBecause == \"QUOTED\" and .sourceDocumentId == \"$SOURCE\" and .matchedWords > 20)] | length == 1" "$report_on"
expect "filter off: quotation counted" '.summary.overlapPercent > 10' "$report_off"

echo "== 4. Synonym rewrite: fingerprints miss it, embeddings suggest it"
report=$(check "$PARAPHRASE")
summarize "$report"
expect "no exact overlap" '.summary.overlapPercent == 0' "$report"
expect "EMBEDDING match from the source paper" \
    "[.matches[] | select(.method == \"EMBEDDING\" and .sourceDocumentId == \"$SOURCE\")] | length >= 1" "$report"
expect "it points at the rewritten paragraph" \
    "[.matches[] | select(.method == \"EMBEDDING\" and .sourceDocumentId == \"$SOURCE\")][0] | (.query.text | contains(\"heavily rusted\")) and (.source.text | contains(\"badly corroded\"))" "$report"
report_fp=$(check "$PARAPHRASE" '{"useEmbeddings":false}')
expect "with embeddings off nothing is reported" '(.matches | length) == 0 and .embedding.status == "NOT_REQUESTED"' "$report_fp"

echo "== 4b. Known limitation: a heavily restructured paraphrase is below the default threshold"
report=$(check "$HEAVY")
expect "not flagged against the source at the default threshold" \
    "[.matches[] | select(.sourceDocumentId == \"$SOURCE\")] | length == 0" "$report"
report=$(check "$HEAVY" '{"minCosine":0.6}')
expect "visible if the reviewer lowers minCosine to 0.6" \
    "[.matches[] | select(.method == \"EMBEDDING\" and .sourceDocumentId == \"$SOURCE\")] | length >= 1" "$report"

echo "== 5. Controls: no false alarms"
report=$(check "$SAME_TOPIC")
summarize "$report"
expect "same topic, original writing: no exact matches" '[.matches[] | select(.method == "FINGERPRINT")] | length == 0' "$report"
expect "same topic, original writing: nothing from the source paper" \
    "[.matches[] | select(.sourceDocumentId == \"$SOURCE\")] | length == 0" "$report"
# Known false alarm, reported rather than asserted: another student's answer on the same topic can pass the
# default paraphrase threshold. See README, Limitations.
jq -r '.matches[] | select(.method == "EMBEDDING") | "  NOTE  paraphrase lead on original writing: \(.sourceTitle), cosine \(.confidence)"' <<<"$report"
report=$(check "$UNRELATED")
expect "unrelated topic: no matches" '(.matches | length) == 0' "$report"

echo "== 6. Every match names its source, span and method; a report can be fetched again"
report=$(check "$VERBATIM")
expect "all matches have method, source and spans" \
    '[.matches[] | .method != null and .sourceDocumentId != null and .query.charEnd > .query.charStart and .source.charEnd > .source.charStart] | all' "$report"
id=$(jq -r .checkId <<<"$report")
fetched=$(curl -fsS "$BASE/api/checks/$id")
expect "GET /api/checks/{id} returns the stored report" ".checkId == \"$id\"" "$fetched"

echo "== 7. Index policy: documents without consent are never sources"
expect "the non-consenting PDF copy did not match the text essay" \
    "[.matches[].sourceDocumentId] | index(\"$VERBATIM_PDF\") == null" "$report"
OTHER=$(curl -fsS -X POST "$BASE/api/collections" -H 'Content-Type: application/json' \
    -d '{"name":"Demo: a different course","indexPolicy":"COURSE_ONLY"}' | jq -r .id)
COPY=$(curl -fsS -X POST "$BASE/api/collections/$OTHER/documents" -F "file=@$SAMPLES/student_verbatim.txt" -F consentToIndex=true | jq -r .id)
report=$(check "$COPY")
expect "COURSE_ONLY documents are not visible from another collection" \
    '.corpus.eligibleDocuments == 0 and (.matches | length) == 0' "$report"

echo "== 8. Replaced characters: Cyrillic look-alikes do not hide a copied paragraph"
report=$(check "$HOMOGLYPH")
summarize "$report"
expect "the disguised paragraph still matches the source paper" \
    "[.matches[] | select(.method == \"FINGERPRINT\" and .sourceDocumentId == \"$SOURCE\" and .matchedWords > 50)] | length == 1" "$report"
expect "the replaced characters are flagged with their offsets" \
    '.flags.replacedCharacters >= 15 and (.flags.replacedCharacterOffsets | length) == .flags.replacedCharacters' "$report"
report=$(check "$VERBATIM")
expect "an ordinary essay has no replaced-character flag" '.flags.replacedCharacters == 0' "$report"

echo "== 9. Hidden text: white text in a DOCX is flagged"
report=$(check "$HIDDEN")
jq -c '.flags | {hiddenTextStatus, hiddenTextWords}' <<<"$report"
expect "hidden text found and counted" '.flags.hiddenTextStatus == "CHECKED" and .flags.hiddenTextWords >= 100' "$report"
report=$(check "$VERBATIM_DOCX")
expect "a normal DOCX has none" '.flags.hiddenTextStatus == "CHECKED" and .flags.hiddenTextWords == 0' "$report"

echo "== 10. Match groups: one paragraph for each group"
report=$(check "$GROUPED" '{"excludeQuotes":false}')
jq -c '[.matches[] | {matchGroup, citation, matchedWords}]' <<<"$report"
jq -c '.matchGroups' <<<"$report"
for group in NOT_CITED_OR_QUOTED MISSING_QUOTATIONS MISSING_CITATION CITED_AND_QUOTED; do
    expect "$group has one match from the source" \
        "[.matches[] | select(.sourceDocumentId == \"$SOURCE\" and .matchGroup == \"$group\")] | length == 1" "$report"
done
expect "group words add up to the matched words" \
    '([.matchGroups[].words] | add) == .summary.matchedWords' "$report"
report=$(check "$GROUPED")
expect "default settings exclude the two quoted matches as QUOTED" \
    '[.excludedMatches[] | select(.excludedBecause == "QUOTED")] | length == 2' "$report"
report=$(check "$GROUPED" '{"excludeCited":true}')
expect "excludeCited removes the cited match with its reason" \
    '[.excludedMatches[] | select(.method == "FINGERPRINT" and .excludedBecause == "CITED" and .matchGroup == "MISSING_QUOTATIONS")] | length == 1' "$report"

echo "== 11. Assignment template: shared question text is excluded"
report=$(check "$TEMPLATE_B")
expect "without a template the two students match on the question text" \
    "[.matches[] | select(.sourceDocumentId == \"$TEMPLATE_A\")] | length >= 1" "$report"
curl -fsS -X PUT "$BASE/api/collections/$COLLECTION/template" -H 'X-Actor: demo' \
    -F "file=@$SAMPLES/assignment_template.txt" | jq -c .
report=$(check "$TEMPLATE_B")
jq -c '{summary: .summary | {totalWords, excludedTemplateWords, matchedWords}, excluded: [.excludedMatches[] | {excludedBecause, matchedWords}]}' <<<"$report"
expect "with the template, nothing is reported against the other student" \
    "[.matches[] | select(.sourceDocumentId == \"$TEMPLATE_A\")] | length == 0" "$report"
expect "the template matches are listed as excluded TEMPLATE" \
    '([.excludedMatches[] | select(.excludedBecause == "TEMPLATE")] | length >= 1) and .summary.excludedTemplateWords > 40' "$report"
report=$(check "$TEMPLATE_B" '{"excludeTemplate":false}')
expect "excludeTemplate=false restores them" \
    "[.matches[] | select(.sourceDocumentId == \"$TEMPLATE_A\")] | length >= 1" "$report"

echo "== 12. Excluding a source and small sources recalculates the report"
report=$(check "$VERBATIM" "{\"excludeSourceIds\":[\"$SOURCE\"]}")
expect "the excluded source has no reported matches" \
    "[.matches[] | select(.sourceDocumentId == \"$SOURCE\")] | length == 0" "$report"
expect "its match is listed as SOURCE_EXCLUDED" \
    "[.excludedMatches[] | select(.method == \"FINGERPRINT\" and .sourceDocumentId == \"$SOURCE\" and .excludedBecause == \"SOURCE_EXCLUDED\")] | length == 1" "$report"
report=$(check "$VERBATIM" '{"minSourcePercent":99}')
expect "minSourcePercent drops every source below it as SMALL_SOURCE" \
    '(.matches | map(select(.method == "FINGERPRINT")) | length) == 0 and ([.excludedMatches[] | select(.excludedBecause == "SMALL_SOURCE")] | length) >= 1' "$report"
report=$(check "$VERBATIM")
expect "sources are ranked and matches carry their source's rank" \
    '(.sources | length) as $n | ((.sources | map(.rank)) == [range(1; $n + 1)]) and ([.matches[] | select(.method == "FINGERPRINT") | .sourceRank != null] | all)' "$report"

echo
if [ "$failures" -eq 0 ]; then
    echo "All demo checks passed."
else
    echo "$failures demo check(s) FAILED."
    exit 1
fi
