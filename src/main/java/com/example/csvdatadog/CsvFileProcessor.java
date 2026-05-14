// Ensuring that the enrichment process complies with SLA
if (enrichmentTakesTooLong()) {
    throw new TimeoutException("Enrichment exceeded SLA.");
}