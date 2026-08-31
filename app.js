import { ROLES, PERMISSIONS } from './permissions.js';
import { MOVIE_SCHEMA } from './movie-schema.js';

const roadmap = [
  ['Foundation v0.1','done'],
  ['البيانات','current'],
  ['الصلاحيات',''],
  ['Firebase',''],
  ['الأفلام',''],
  ['المشاهدة والترجمات',''],
  ['الرفع وR2',''],
  ['لوحة المالك','']
];

const currentRole = 'visitor';

// حارس بسيط يمنع إعادة تهيئة نفس الصفحة إذا تم تحميل الوحدة أكثر من مرة.
// تنظيف دفاعي: إذا قامت الاستضافة/القالب بإدراج نسخة ثانية من الصفحة،
// نُبقي أول محتوى فقط حتى لا تظهر «مراحل البناء» و«حالة المشروع» مرتين.
document.querySelectorAll('main.container').forEach((el, i) => { if (i > 0) el.remove(); });
document.querySelectorAll('footer').forEach((el, i) => { if (i > 0) el.remove(); });

const alreadyInitialized = document.documentElement.dataset.subhubInitialized === 'true';
if (alreadyInitialized) {
  // لا نرسم أي نسخة ثانية من الأقسام.
} else {
  document.documentElement.dataset.subhubInitialized = 'true';
document.getElementById('roleBadge').textContent = currentRole;

document.getElementById('roles').innerHTML = ROLES.map(r => `
  <div class="row"><span>${r.label}</span><span class="key">${r.id}</span></div>`).join('');

document.getElementById('schema').textContent = JSON.stringify(MOVIE_SCHEMA, null, 2);
document.getElementById('roadmap').innerHTML = roadmap.map(([name,state],i)=>`<li class="${state}">${i+1}) ${name}</li>`).join('');

document.getElementById('testUiBtn').addEventListener('click',()=>{
  const p=PERMISSIONS[currentRole];
  document.getElementById('status').textContent = `اختبار ناجح — الدور ${currentRole} — التصفح: ${p.browse ? 'نعم':'لا'} — الإدارة: ${p.manage ? 'نعم':'لا'}`;
});

}
