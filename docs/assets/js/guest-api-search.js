const input = document.getElementById("api-search");
const results = document.getElementById("api-search-results");

if (input && results) {
  const apiRoot = new URL(input.dataset.apiRoot, window.location.origin);
  const indexUrl = new URL("search.json", apiRoot);
  fetch(indexUrl)
    .then((response) => {
      if (!response.ok) throw new Error(`API index returned ${response.status}`);
      return response.json();
    })
    .then((entries) => {
      input.addEventListener("input", () => {
        const query = input.value.trim().toLocaleLowerCase();
        results.replaceChildren();
        results.hidden = !query;
        if (!query) return;
        const matches = entries
          .filter((entry) => `${entry.symbol} ${entry.signature}`.toLocaleLowerCase().includes(query))
          .slice(0, 50);
        if (!matches.length) {
          results.textContent = "No matching declarations";
          return;
        }
        for (const entry of matches) {
          const item = document.createElement("a");
          item.href = new URL(entry.url.replace(/^\/guest-api\//, ""), apiRoot);
          item.textContent = `${entry.symbol} — ${entry.signature}`;
          results.append(item);
        }
      });
    })
    .catch(() => {
      input.disabled = true;
      input.placeholder = "Search is temporarily unavailable";
    });
}
