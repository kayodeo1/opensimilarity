# Gaps and improvements vs public Turnitin pain points

Clean-room opportunities for an open product. Evidence separated from opinion. Compiled 2026-11-06.

## 1. Privacy, retention, and student agency

**Evidence**

- *A.V. v. iParadigms*, 562 F.3d 630 (4th Cir. 2009): archiving student papers for plagiarism detection held to be fair use (transformative, limited third-party disclosure). Summary: [Copyright Office case brief PDF](https://www.copyright.gov/) / local copy of 562 F.3d materials in tool-results.
- Morris & Stommel, “A Guide for Resisting Edtech: the Case against Turnitin,” Hybrid Pedagogy: critique of perpetual licenses, student IP, policing pedagogy. [hybridpedagogy.org/resisting-edtech](https://hybridpedagogy.org/resisting-edtech/).
- Dutch HE sector (e.g. Erasmus) pressed for GDPR-compatible terms or alternatives (2018 reporting). [Erasmus Magazine](https://www.erasmusmagazine.nl/en/2018/02/22/nieuwe-voorwaarden-of-alternatief-voor-plagiaatsoftware-turnitin/).

**Improvement bets**

1. **Local / on-prem first** with optional federated matching; default = institution-owned store, student deletion and non-indexing as first-class controls.
2. **Explicit consent UX** for repository deposit (opt-in per assignment, expiry, export, hard delete).
3. **No perpetual worldwide license** language; time-bounded processing agreements aligned with NDPR / GDPR / POPIA style duties.

## 2. Score misinterpretation and false positives

**Evidence**

- TeSToP (Foltýnek et al., 2020): 15 web text-matching systems; none find all plagiarism; some flag non-plagiarized material. Data: [academicintegrity.eu WG testing](http://www.academicintegrity.eu/wp/wg-testing/).
- Similarity % is overlap, not a plagiarism verdict (Turnitin’s own guides stress human review).

**Improvement bets**

4. **Explainable matches**: show exact span, source URL / doc id, algorithm (fingerprint vs embedding), and why a filter did or did not apply.
5. **Pedagogy-first defaults**: suppress raw % in student view; show match groups and revision suggestions first.

## 3. Opaque cross-institution matches

**Evidence / commonly reported educator pain**

- Matches against other institutions’ papers often cannot be opened without requesting access; instructors see a percentage without the source text (*widely reported in educator forums; treat as operational complaint, not a measured study*).

**Improvement bets**

6. **Redacted side-by-side** with differential privacy style previews, or hash-only confirmation until both institutions approve reveal.
7. **Open match protocol** between consenting schools (shared fingerprint index, no full-text dump).

## 4. AI writing detection reliability and equity

**Evidence**

- Vendor: <1% document FP above 20% AI band; hides 1–19% as `*%`. [Turnitin FP blog](https://www.turnitin.com/blog/understanding-the-false-positive-rate-for-sentences-of-our-ai-writing-detection-capability); [AI Writing Report guide](https://guides.turnitin.com/hc/en-us/articles/22774058814093-Using-the-AI-Writing-Report).
- Vanderbilt disabled the detector (2023) citing ~750 hypothetical FPs on 75k papers and ESL risk. [Vanderbilt guidance](https://www.vanderbilt.edu/brightspace/2023/08/16/guidance-on-ai-detection-and-why-were-disabling-turnitins-ai-detector/).
- Liang, Yuksekgonul, Mao, Wu, Zou (2023): GPT detectors biased against non-native English writers. [Patterns / Cell](https://www.cell.com/patterns/fulltext/S2666-3899(23)00130-7).
- Weber-Wulff et al. / Springer educational integrity testing (2023): many AI detectors unreliable (<80% accuracy). [Springer article](https://link.springer.com/article/10.1007/s40979-023-00146-z).
- Open Information Science 2025 study on Turnitin AI: ~1% FP on 2016 human psych intros; higher FN especially on newer GPT models. DOI [10.1515/opis-2025-0056](https://doi.org/10.1515/opis-2025-0056).
- Temple teaching-center evaluation: stronger on pure human / pure AI than hybrids; sentence flags poorly aligned with true AI spans. [Temple PDF](https://teaching.temple.edu/sites/teaching/files/media/document/Evaluating%20the%20Effectiveness%20of%20Turnitin%E2%80%99s%20AI%20Writing%20Indicator%20Model_0.pdf).

**Improvement bets**

8. **Defer AI-authorship claims in MVP**; if offered later, ship as optional research signal with calibration cards, ESL test sets, and never as misconduct proof.
9. **Process evidence over classifiers**: draft history, citation graphs, oral defense prompts.

## 5. Multilingual and African-language equity

**Evidence**

- TeSToP multilingual tests showed uneven tool quality across languages.
- Marketing “170 languages” claim is not the same as equal recall (*uncertain without independent audit*).

**Improvement bets**

10. Prioritize **English + major Nigerian HE languages** (Yoruba, Hausa, Igbo) and Swahili via Masakhane / AfriBERTa-class models where licenses allow; publish per-language evals.

## 6. Cost and lock-in

**Evidence**

- Sector reporting on seeking alternatives under GDPR cost / contract pressure (Erasmus Magazine 2018 and later HE procurement discussions).

**Improvement bets**

11. **Apache-2.0 / MIT core**, self-host on Postgres, transparent pricing for managed cloud.
12. **Export everything** (reports, fingerprints, embeddings) so institutions can leave.

## 7. Generative / semantic plagiarism (2026 landscape)

**Evidence**

- PAN 2026 generative plagiarism task overview: shift from lexical overlap to semantic retrieval against LLM-rewritten scientific text; 26 submissions from 6 teams beat baselines. Local PDF: “Overview of the Plagiarism Detection Task at PAN 2026”.

**Improvement bets**

13. Dual pipeline: **winnowing fingerprints** for verbatim / near-copy + **embedding retrieval** for paraphrase; evaluate on PAN-style generative sets.
