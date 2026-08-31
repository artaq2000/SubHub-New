import { ROLES, PERMISSIONS } from "./permissions.js";
import { MOVIE_SCHEMA } from "./movie-schema.js";

const roleBadge = document.querySelector("#roleBadge");
const rolesEl = document.querySelector("#roles");
const schemaEl = document.querySelector("#schema");
const roadmapEl = document.querySelector("#roadmap");
const statusEl = document.querySelector("#status");
const testUiBtn = document.querySelector("#testUiBtn");

const ROADMAP = [
  "Foundation v0.1",
  "البيانات",
  "الصلاحيات",
  "Firebase",
  "الأفلام",
  "المشاهدة والترجمات",
  "الرفع و R2",
  "لوحة المالك"
];

function renderRoles() {
  rolesEl.replaceChildren();

  for (const role of ROLES) {
    const row = document.createElement("div");
    row.className = "role-row";

    const label = document.createElement("span");
    label.textContent = role.label;

    const badge = document.createElement("span");
    badge.className = "mini-badge";
    badge.textContent = role.id;

    row.append(label, badge);
    rolesEl.appendChild(row);
  }
}

function renderSchema() {
  schemaEl.textContent = JSON.stringify(MOVIE_SCHEMA, null, 2);
}

function renderRoadmap() {
  roadmapEl.replaceChildren();

  // مهم: لا نكتب "1." داخل النص لأن <ol> يضيف الترقيم تلقائيًا.
  ROADMAP.forEach((item, index) => {
    const li = document.createElement("li");
    li.textContent = item;
    if (index === 0) li.classList.add("current");
    roadmapEl.appendChild(li);
  });
}

function testUi() {
  const previous = statusEl.textContent;
  statusEl.textContent = "تم اختبار الواجهة بنجاح ✓";
  statusEl.classList.add("success");

  window.setTimeout(() => {
    statusEl.textContent = previous;
    statusEl.classList.remove("success");
  }, 1800);
}

function init() {
  roleBadge.textContent = "visitor";
  renderRoles();
  renderSchema();
  renderRoadmap();
  testUiBtn.addEventListener("click", testUi);
}

init();
