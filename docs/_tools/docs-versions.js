/* SPDX-License-Identifier: Apache-2.0 */
document.querySelectorAll('.docs-version-bar a[data-equivalent-page]').forEach((link) => {
  link.hash = window.location.hash;
});
