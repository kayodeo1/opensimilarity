# Turnitin Similarity Report behaviour, and how OpenSimilarity reproduces it

Compiled 2026-11-15 from Turnitin's public help guides, university guides that describe it, and one published patent
application. There was no Turnitin account, binary or private API in this work, so everything below is either a
quotation of public documentation or an inference from it. Each row says which.

Turnitin's matching runs on its own servers and is never shipped, so its algorithm cannot be recovered by decompiling
anything. What can be reproduced is the behaviour its guides describe. The algorithms OpenSimilarity uses for that are
published ones (winnowing, MinHash, sentence embeddings), not Turnitin's.

## Features reproduced

| Turnitin behaviour (public source) | Evidence | OpenSimilarity |
| --- | --- | --- |
| **Match groups.** Matches split into Not Cited or Quoted, Missing Quotations, Missing Citation, and Cited and Quoted, "based on whether Turnitin detected in-text citations and/or quotation marks". [Match Groups guide][groups] | Documented | `matchGroup` on every match and a `matchGroups` summary. The four-way rule follows the guide exactly. **How citations are detected is not documented**, so `filter/Citations.java` uses its own patterns: author-year (APA, Harvard) and bracketed numbers (IEEE, Vancouver), within 200 characters of the match in the same paragraph. |
| **Hiding a group recalculates the score.** Hiding Cited and Quoted makes the overall percentage "instantly recalculate". [Lethbridge guide][leth] | Secondary source | `excludeCited` removes matches with a detected citation and recalculates; `excludeQuotes` already removed quoted ones. |
| **Replaced characters flag.** Letters swapped for look-alikes from another alphabet; Turnitin "automatically swaps the replaced characters for the correct one" when building the report. [Flags guide][flags] | Documented | `fingerprint/Confusables.java` maps Cyrillic, Greek and Armenian look-alikes to Latin. The tokenizer folds them so matching still works, and the report's `flags.replacedCharacters` lists each offset. The look-alike list is this project's own selection. |
| **Hidden text flag.** Text "changed to be the same color as the background", used to inflate word count or throw off matching. [Flags guide][flags] | Documented | `ingest/HiddenTextDetector.java` checks DOCX runs coloured like the page background, marked hidden, or 1 pt or smaller, and PDF glyphs filled white. It runs at upload, because the original file is not kept. |
| **Template exclusion.** Works "by matching phrases or sentences from the template", so edited template text may not be excluded; uploaded before any submission. [Template guide][tmpl], [Conestoga][conestoga] | Documented | `PUT /api/collections/{id}/template`. Template words are aligned like any source (5-word runs, merged across up to 4 changed words) and masked. Unlike Turnitin, it is applied when a check runs, so it can be added or changed after submissions. |
| **Exclude small matches** by word count, or sources "less than 10% in total". [NCCU guide][nccu], [Lincoln guide][lincoln] | Secondary source | `minMatchWords` (existing) and new `minSourcePercent`. Matches dropped this way are listed as `SMALL_SOURCE`. |
| **Exclude a source**, and the similarity index is recalculated. [Lincoln guide][lincoln] | Secondary source | `excludeSourceIds`; matches are listed as `SOURCE_EXCLUDED`. The UI has an Exclude button per source. |
| **Numbered sources, highest first** (Match Overview). [BUE guide][bue] | Secondary source | `sources[].rank` and `matches[].sourceRank`. |
| **Score from the word count of the whole paper.** [Lincoln guide][lincoln] | Secondary source; exact formula unpublished | New `summary.overlapPercentOfAllWords`. The existing `overlapPercent` divides by words left after filters, which is the more honest figure when large parts are excluded. |
| **Text exclusion hash** to drop shared prompt text from a report. [US 2013/0073575, iParadigms][patent] | Patent application abstract, read via a third-party mirror; not confirmed as used today | Same goal as template exclusion. OpenSimilarity aligns template words directly rather than hashing them. |

## Not reproduced

- **Turnitin's corpus.** Its scale (web pages, publisher content, student papers) is the main reason its results differ.
  OpenSimilarity compares only documents uploaded to it.
- **AI writing detection.** Deliberately left out; see `gaps-and-improvements.md` section 4.
- **The internal matching algorithm.** Not public. A 2010 article quotes a company official saying it looks for
  "patterns in the language" rather than plain strings ([Campus Technology][ct]); that is too vague to copy.
- **Report look and naming.** The four group names come from the guides because reviewers already know them. The layout
  is OpenSimilarity's own.

## What would make the comparison measurable

Submitting the files in `samples/` to a licensed Turnitin account, under the institution's terms, and comparing the two
reports would turn the "Secondary source" rows into observed behaviour. That has not been done.

[groups]: https://guides.turnitin.com/hc/en-us/articles/28057483210637-How-do-the-Match-Groups-work-in-the-new-Similarity-Report
[leth]: https://sites.ulethbridge.ca/moodle-answers/?p=5791
[flags]: https://help.turnitin.com/feedback-studio/flags.htm
[tmpl]: https://help.turnitin.com/feedback-studio/lti13/instructor/excluding-assignment-templates.htm
[conestoga]: https://lms.conestogac.on.ca/facultysupport/assignment-tii-template
[nccu]: https://lib.video.nccu.edu.tw/p/TurnitinInstructor8
[lincoln]: https://digitaleducation.lincoln.ac.uk/?p=7839
[bue]: https://bue.libguides.com/Turnitin2_Instructors/View_originality_reports
[patent]: https://uspto.report/patent/app/20130073575
[ct]: https://campustechnology.com/articles/2010/03/01/plagiarism-may-be-rife-in-higher-ed-applications.aspx?p=1
