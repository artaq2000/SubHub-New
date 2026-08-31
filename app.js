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
document.getElementById('roleBadge').textContent = currentRole;

document.getElementById('roles').innerHTML = ROLES.map(r => `
  <div class="row"><span>${r.label}</span><span class="key">${r.id}</span></div>`).join('');

document.getElementById('schema').textContent = JSON.stringify(MOVIE_SCHEMA, null, 2);
document.getElementById('roadmap').innerHTML = roadmap.map(([name,state],i)=>`<li class="${state}">${i+1}) ${name}</li>`).join('');

document.getElementById('testUiBtn').addEventListener('click',()=>{
  const p=PERMISSIONS[currentRole];
  document.getElementById('status').textContent = `اختبار ناجح — الدور ${currentRole} — التصفح: ${p.browse ? 'نعم':'لا'} — الإدارة: ${p.manage ? 'نعم':'لا'}`;
});
