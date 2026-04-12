package net.olrecon.maven.dg.plugin.util;

import net.olrecon.maven.dg.plugin.model.ParentInfo;
import net.olrecon.maven.dg.plugin.model.ParentVersionIssue;

import java.util.List;
import java.util.Map;

/**
 * Generator for the HTML report of parent POM version check results.
 * Produces a self-contained HTML file (no external dependencies) with a violations table.
 */
public class HtmlReportGenerator {

    private final String projectCoords;
    private final List<String> targetGroupIds;
    private final String minVersion;
    private final String timestamp;
    private final int totalErrors;
    private final Map<String, List<ParentVersionIssue>> errorsByModule;

    public HtmlReportGenerator(String projectCoords,
                                List<String> targetGroupIds,
                                String minVersion,
                                String timestamp,
                                int totalErrors,
                                Map<String, List<ParentVersionIssue>> errorsByModule) {
        this.projectCoords = projectCoords;
        this.targetGroupIds = targetGroupIds;
        this.minVersion = minVersion;
        this.timestamp = timestamp;
        this.totalErrors = totalErrors;
        this.errorsByModule = errorsByModule;
    }

    /** Generates the complete HTML document and returns it as a string */
    public String generate() {
        StringBuilder sb = new StringBuilder();
        sb.append(htmlHeader());
        sb.append(bodySummary());
        if (totalErrors == 0) {
            sb.append(successBanner());
        } else {
            sb.append(errorsTable());
        }
        sb.append(htmlFooter());
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // HTML blocks
    // -------------------------------------------------------------------------

    private String htmlHeader() {
        return "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "<meta charset=\"UTF-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "<title>Dependency Governance Report</title>\n"
                + "<style>\n"
                + css()
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "<div class=\"page\">\n"
                + "<header class=\"page-header\">\n"
                + "  <div class=\"header-title\">\n"
                + "    <span class=\"logo\">&#128269;</span>\n"
                + "    <h1>Dependency Governance Report</h1>\n"
                + "  </div>\n"
                + "  <div class=\"header-meta\">\n"
                + "    <span class=\"badge badge-project\">" + esc(projectCoords) + "</span>\n"
                + "    <span class=\"badge badge-neutral\">Generated: " + esc(timestamp) + "</span>\n"
                + "  </div>\n"
                + "</header>\n";
    }

    private String bodySummary() {
        String statusClass = totalErrors == 0 ? "stat-ok" : "stat-error";
        String statusIcon  = totalErrors == 0 ? "&#10003;" : "&#9888;";
        String statusLabel = totalErrors == 0 ? "PASSED" : "FAILED";
        int modulesWithErrors = errorsByModule.size();

        return "<section class=\"summary\">\n"
                + "  <div class=\"stat-card " + statusClass + "\">\n"
                + "    <div class=\"stat-icon\">" + statusIcon + "</div>\n"
                + "    <div class=\"stat-label\">Status</div>\n"
                + "    <div class=\"stat-value\">" + statusLabel + "</div>\n"
                + "  </div>\n"
                + "  <div class=\"stat-card\">\n"
                + "    <div class=\"stat-label\">Total Violations</div>\n"
                + "    <div class=\"stat-value " + (totalErrors > 0 ? "text-red" : "text-green") + "\">" + totalErrors + "</div>\n"
                + "  </div>\n"
                + "  <div class=\"stat-card\">\n"
                + "    <div class=\"stat-label\">Modules with Errors</div>\n"
                + "    <div class=\"stat-value " + (modulesWithErrors > 0 ? "text-red" : "text-green") + "\">" + modulesWithErrors + "</div>\n"
                + "  </div>\n"
                + "  <div class=\"stat-card\">\n"
                + "    <div class=\"stat-label\">Target Group(s)</div>\n"
                + "    <div class=\"stat-value stat-value-sm\">" + esc(String.join("<br>", targetGroupIds)) + "</div>\n"
                + "  </div>\n"
                + "  <div class=\"stat-card\">\n"
                + "    <div class=\"stat-label\">Min Version</div>\n"
                + "    <div class=\"stat-value\">" + esc(minVersion) + "</div>\n"
                + "  </div>\n"
                + "</section>\n";
    }

    private String successBanner() {
        return "<section class=\"success-banner\">\n"
                + "  <span class=\"success-icon\">&#10003;</span>\n"
                + "  <div>\n"
                + "    <strong>All dependencies are compliant.</strong><br>\n"
                + "    No parent POM version violations were found.\n"
                + "  </div>\n"
                + "</section>\n";
    }

    private String errorsTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("<section class=\"violations\">\n");
        sb.append("  <h2>Violations by Module</h2>\n");

        for (Map.Entry<String, List<ParentVersionIssue>> entry : errorsByModule.entrySet()) {
            String module = entry.getKey();
            List<ParentVersionIssue> issues = entry.getValue();

            sb.append("  <details open class=\"module-block\">\n");
            sb.append("    <summary class=\"module-summary\">\n");
            sb.append("      <span class=\"module-name\">&#128190; ").append(esc(module)).append("</span>\n");
            sb.append("      <span class=\"module-count badge badge-error\">").append(issues.size()).append(" violation").append(issues.size() != 1 ? "s" : "").append("</span>\n");
            sb.append("    </summary>\n");
            sb.append("    <div class=\"table-wrapper\">\n");
            sb.append("    <table>\n");
            sb.append("      <thead><tr>\n");
            sb.append("        <th>#</th>\n");
            sb.append("        <th>Library</th>\n");
            sb.append("        <th>Parent Version</th>\n");
            sb.append("        <th>Min Expected</th>\n");
            sb.append("        <th>Introduced By</th>\n");
            sb.append("        <th>Parent Chain</th>\n");
            sb.append("      </tr></thead>\n");
            sb.append("      <tbody>\n");

            int idx = 1;
            for (ParentVersionIssue issue : issues) {
                sb.append(issueRow(idx++, issue));
            }

            sb.append("      </tbody>\n");
            sb.append("    </table>\n");
            sb.append("    </div>\n");
            sb.append("  </details>\n");
        }

        sb.append("</section>\n");
        return sb.toString();
    }

    private String issueRow(int idx, ParentVersionIssue issue) {
        String library = esc(nvl(issue.getLibraryGroupId()) + ":" + nvl(issue.getLibraryArtifactId()));
        String libraryVersion = esc(nvl(issue.getLibraryVersion()));
        String parentVersion = esc(nvl(issue.getParentVersion()));
        String minExpected = esc(nvl(issue.getMinExpectedVersion()));
        String sourceLib = nvl(issue.getSourceLibrary());
        String sourceVer = nvl(issue.getSourceVersion());
        String introducedBy = sourceLib.isEmpty()
                ? "<span class=\"text-muted\">direct</span>"
                : esc(sourceLib + ":" + sourceVer);

        String chainHtml = buildChainHtml(issue.getParentChain());

        return "        <tr>\n"
                + "          <td class=\"text-center text-muted\">" + idx + "</td>\n"
                + "          <td><strong>" + library + "</strong><br><span class=\"text-muted\">" + libraryVersion + "</span></td>\n"
                + "          <td><span class=\"badge badge-error\">" + parentVersion + "</span></td>\n"
                + "          <td><span class=\"badge badge-ok\">&ge;" + minExpected + "</span></td>\n"
                + "          <td>" + introducedBy + "</td>\n"
                + "          <td class=\"chain-cell\">" + chainHtml + "</td>\n"
                + "        </tr>\n";
    }

    private String buildChainHtml(List<ParentInfo> chain) {
        if (chain == null || chain.isEmpty()) {
            return "<span class=\"text-muted\">—</span>";
        }
        StringBuilder sb = new StringBuilder("<ol class=\"chain-list\">");
        for (ParentInfo p : chain) {
            String coords = nvl(p.getGroupId()) + ":" + nvl(p.getArtifactId()) + ":" + nvl(p.getVersion());
            sb.append("<li>").append(esc(coords)).append("</li>");
        }
        sb.append("</ol>");
        return sb.toString();
    }

    private String htmlFooter() {
        return "<footer class=\"page-footer\">\n"
                + "  Generated by <strong>dependency-check-plugin</strong> &mdash; "
                + "<a href=\"https://github.com/andrey-dubchenko/depenency-check-plugin\" target=\"_blank\">GitHub</a>\n"
                + "</footer>\n"
                + "</div>\n"
                + "<script>\n"
                + js()
                + "</script>\n"
                + "</body>\n"
                + "</html>\n";
    }

    // -------------------------------------------------------------------------
    // CSS and JS
    // -------------------------------------------------------------------------

    private String css() {
        return "*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }\n"
                + "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;"
                + " background: #f0f2f5; color: #24292e; font-size: 14px; line-height: 1.5; }\n"
                + ".page { max-width: 1300px; margin: 0 auto; padding: 24px 16px; }\n"
                // header styles
                + ".page-header { background: #1e2a3a; color: #fff; border-radius: 10px; padding: 20px 28px;"
                + " margin-bottom: 20px; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }\n"
                + ".header-title { display: flex; align-items: center; gap: 12px; }\n"
                + ".header-title h1 { font-size: 22px; font-weight: 700; }\n"
                + ".logo { font-size: 28px; }\n"
                + ".header-meta { display: flex; flex-wrap: wrap; gap: 8px; }\n"
                // badge styles
                + ".badge { display: inline-block; padding: 3px 10px; border-radius: 12px; font-size: 12px; font-weight: 600; white-space: nowrap; }\n"
                + ".badge-project { background: #3b4f66; color: #e0e8f0; }\n"
                + ".badge-neutral { background: #4a5568; color: #e2e8f0; }\n"
                + ".badge-error { background: #fee2e2; color: #b91c1c; }\n"
                + ".badge-ok { background: #dcfce7; color: #15803d; }\n"
                // summary card styles
                + ".summary { display: flex; gap: 14px; flex-wrap: wrap; margin-bottom: 20px; }\n"
                + ".stat-card { background: #fff; border-radius: 10px; padding: 18px 22px; flex: 1; min-width: 140px;"
                + " box-shadow: 0 1px 4px rgba(0,0,0,.08); text-align: center; }\n"
                + ".stat-icon { font-size: 28px; margin-bottom: 4px; }\n"
                + ".stat-label { font-size: 11px; text-transform: uppercase; letter-spacing: .06em; color: #6b7280; margin-bottom: 6px; }\n"
                + ".stat-value { font-size: 26px; font-weight: 700; color: #1e2a3a; }\n"
                + ".stat-value-sm { font-size: 13px; font-weight: 600; }\n"
                + ".stat-ok .stat-icon { color: #16a34a; }\n"
                + ".stat-ok .stat-value { color: #16a34a; }\n"
                + ".stat-error .stat-icon { color: #dc2626; }\n"
                + ".stat-error .stat-value { color: #dc2626; }\n"
                + ".text-red { color: #dc2626 !important; }\n"
                + ".text-green { color: #16a34a !important; }\n"
                + ".text-muted { color: #9ca3af; }\n"
                + ".text-center { text-align: center; }\n"
                // success banner styles
                + ".success-banner { background: #f0fdf4; border: 1.5px solid #86efac; border-radius: 10px;"
                + " padding: 22px 28px; display: flex; align-items: center; gap: 16px; font-size: 15px; color: #166534; }\n"
                + ".success-icon { font-size: 32px; color: #16a34a; }\n"
                // violations styles
                + ".violations h2 { font-size: 16px; font-weight: 700; margin-bottom: 14px; color: #1e2a3a; }\n"
                + ".module-block { background: #fff; border-radius: 10px; box-shadow: 0 1px 4px rgba(0,0,0,.08);"
                + " margin-bottom: 14px; overflow: hidden; }\n"
                + ".module-summary { display: flex; align-items: center; justify-content: space-between;"
                + " padding: 14px 20px; cursor: pointer; list-style: none; user-select: none;"
                + " background: #f8fafc; border-bottom: 1px solid #e5e7eb; }\n"
                + ".module-summary::-webkit-details-marker { display: none; }\n"
                + ".module-summary::before { content: '\\25B6'; margin-right: 8px; font-size: 11px; color: #6b7280; transition: transform .2s; }\n"
                + "details[open] .module-summary::before { transform: rotate(90deg); }\n"
                + ".module-name { font-weight: 700; font-size: 15px; color: #1e2a3a; }\n"
                + ".module-count { font-size: 12px; }\n"
                // table styles
                + ".table-wrapper { overflow-x: auto; }\n"
                + "table { width: 100%; border-collapse: collapse; font-size: 13px; }\n"
                + "thead th { background: #f1f5f9; padding: 10px 14px; text-align: left;"
                + " font-size: 11px; text-transform: uppercase; letter-spacing: .05em; color: #475569;"
                + " border-bottom: 2px solid #e2e8f0; white-space: nowrap; }\n"
                + "tbody tr { border-bottom: 1px solid #f1f5f9; }\n"
                + "tbody tr:hover { background: #f8fafc; }\n"
                + "tbody td { padding: 10px 14px; vertical-align: top; }\n"
                // chain list styles
                + ".chain-list { margin: 0; padding: 0; list-style: none; }\n"
                + ".chain-list li { padding: 1px 0; color: #475569; font-size: 12px; }\n"
                + ".chain-list li + li::before { content: '↑ '; color: #9ca3af; }\n"
                + ".chain-cell { max-width: 320px; }\n"
                // footer styles
                + ".page-footer { margin-top: 28px; text-align: center; color: #9ca3af; font-size: 12px; padding: 12px; }\n"
                + ".page-footer a { color: #6b7280; }\n";
    }

    private String js() {
        // Small JS snippet: "Expand All / Collapse All" button (optional UX)
        return "document.querySelectorAll('details.module-block').forEach(function(d) {\n"
                + "  d.querySelector('summary').addEventListener('click', function() {\n"
                + "    // native toggle is handled by browser\n"
                + "  });\n"
                + "});\n";
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    /** HTML-escapes special characters */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** Returns an empty string instead of null */
    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
