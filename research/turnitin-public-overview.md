# Turnitin public overview (clean-room notes)

**Scope:** Public product docs, marketing pages, help guides, academic papers, and court summaries only. No authenticated Turnitin sessions, no binaries, no reverse engineering. Compiled 2026-11-06. Claims marked *uncertain* when the only source is marketing copy or a secondary summary.

## Product surface (as publicly marketed)

- **Feedback Studio** – core undergraduate writing, feedback, grading, and similarity product. Source: [turnitin.com/products/feedback-studio](https://www.turnitin.com/products/feedback-studio/).
- **Originality** – positioned as an integrity add-on covering AI writing detection, AI paraphrasing / bypass signals, authorship / contract-cheating insights, and ProQuest thesis access. Source: [turnitin.com/products/originality](https://www.turnitin.com/products/originality/).
- **Similarity / SimCheck** – similarity-focused SKUs with repository comparison and report filters. Sources: [Similarity product page](https://www.turnitin.com/products/similarity/), [account settings guide](https://guides.turnitin.com/hc/en-us/articles/21745851240589-Customizing-account-settings-for-Similarity-and-SimCheck).
- **iThenticate** – research / publisher similarity (and AI writing) checks for high-stakes scholarship. Source: [turnitin.com/products/ithenticate](https://www.turnitin.com/products/ithenticate/).
- **Gradescope** – assessment / grading platform (paper, digital, programming, bubble sheets). Source: [turnitin.com/products/gradescope](https://www.turnitin.com/products/gradescope/).
- Broader lineup also marketed under ExamSoft, Crossref Similarity Check partnerships, Draft Coach, Clarity, etc. Exact bundling and regional naming change often; treat portfolio charts on turnitin.com/products as *marketing-dated*.

## Similarity Report workflows (public help docs)

Overall similarity is described as matching words divided by total words in the submission; matched spans are highlighted. Source: [Overview of the new Similarity Report experience](https://guides.turnitin.com/hc/en-us/articles/24194876779661-Overview-of-the-new-Similarity-Report-experience).

**Match Groups** (enhanced report) categorize matches by citation / quotation usage:

| Group | Public meaning |
| --- | --- |
| Not Cited or Quoted | Match with neither quotation marks nor citation |
| Missing Quotations | Cited but exact enough that quotes may be needed |
| Missing Citation | Quoted but missing a citation |
| Cited and Quoted | Quoted and cited (still reviewable for style) |

Source: same overview guide above.

**Filters and exclusions** (per submission or assignment defaults):

- Compare against student database, internet, and periodicals / journals / publications.
- Exclude bibliography, quoted text, cited text, and small matches.
- Exclude entire repositories from the search set when the org allows it.

Sources: [Managing filters and exclusions](https://guides.turnitin.com/hc/en-us/articles/23476785544973-Managing-filters-and-exclusions-in-the-Similarity-Report), [enhanced report filters](https://guides.turnitin.com/hc/en-us/articles/27396134275597-Managing-filters-and-exclusions-in-the-new-enhanced-Similarity-Report).

**Flags panel** – marketing and product copy describe flags for manipulated text (replaced or hidden characters). Source: Similarity product page (search-indexed summary; *confirm live page when unblocked*).

## Matching claims (repository sources)

Public admin / guide docs list searchable repositories:

- **Internet** – web crawler archive; marketing claims “over 70 billion” current and removed pages (*marketing figure; date not pinned in guide text*).
- **Publications** – publisher partnerships; marketing claims “over 170 million” articles (*marketing*).
- **Crossref** and **Crossref Posted Content** – published and pre-publication scholarly works via Crossref partnership.
- **Submitted works** – private institutional, private group (country / consortium), and global student-paper repositories, depending on license.

Sources: [filters guide](https://guides.turnitin.com/hc/en-us/articles/23476785544973-Managing-filters-and-exclusions-in-the-Similarity-Report), [account settings](https://guides.turnitin.com/hc/en-us/articles/21745851240589-Customizing-account-settings-for-Similarity-and-SimCheck).

**Indexing controls:** Index all submissions, allow users to choose, or don’t index. Indexing can feed the org private repo and, depending on license, Turnitin’s shared submitted-works repo. Source: account settings guide above.

**Languages:** Similarity product marketing claims comparison across “170 languages” (*marketing; independent multilingual recall is a known weak spot in TeSToP 2020*).

## AI writing detection (public claims)

- Score is independent of the similarity percentage; AI highlights are not the same as Similarity Report highlights. Source: [Using the AI Writing Report](https://guides.turnitin.com/hc/en-us/articles/22774058814093-Using-the-AI-Writing-Report).
- Vendor guidance: must not be sole basis for adverse action; needs human judgment and institutional policy. Same guide.
- Document-level false positive claim: **less than 1%** for documents with **≥20%** likely AI content. Sentence-level FP claim ~**4%**. Sources: [Understanding false positive rates](https://www.turnitin.com/blog/understanding-the-false-positive-rate-for-sentences-of-our-ai-writing-detection-capability), leadership blog mirror.
- Scores in the **1–19%** band are **not surfaced** as a number (shown as `*%`) because that band has higher false positives; change applied for reports after ~8–16 July 2024. Source: [AI writing detection model changelog](https://guides.turnitin.com/hc/en-us/articles/28294949544717-AI-writing-detection-model).
- July 2024: interactive categories “AI-generated only” vs “AI-generated then AI-paraphrased”; max qualifying text raised to 30,000 words. Same changelog.
- October 2025: model update for recall; not retroactive without resubmit. Same changelog.

## Integrations (public)

LMS / LTI integrations are publicly advertised for Canvas, Moodle, Blackboard, Brightspace, Google Classroom, Microsoft Teams, etc. Exact LTI version matrix and Core API (TCA) endpoints sit behind developer docs; *details not fully verified in this pass when guides.turnitin.com returns 403 to some automated fetches*.

## Known public limits (summary; detail in gaps doc)

- Text-matching tools miss paraphrase, translation, and multi-source patchwriting; TeSToP 2020 tested 15 systems including commercial ones and found incomplete recall and some false alarms (Foltýnek et al., 11 Feb 2020). Local PDF: TeSToP paper in Claude tool-results.
- AI detectors are contested: institutions such as Vanderbilt disabled Turnitin’s AI detector (Aug 2023) citing FP scale and ESL bias concerns. Source: [Vanderbilt Brightspace guidance](https://www.vanderbilt.edu/brightspace/2023/08/16/guidance-on-ai-detection-and-why-were-disabling-turnitins-ai-detector/).
- Student-paper archiving upheld as fair use in *A.V. ex rel. Vanderhye v. iParadigms*, 562 F.3d 630 (4th Cir. 2009), but remains ethically contested (Morris & Stommel, Hybrid Pedagogy).

## Gaps in this research pass

- Official marketing homepage and some guides returned **403** to automated fetch; relied on search snippets + alternate guide URLs.
- No public patent deep-dive completed here ( deferred ).
- Live API schema / TCA rate limits not captured.
