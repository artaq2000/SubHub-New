export const ROLES = [
  { id:'visitor', label:'زائر' },
  { id:'free', label:'مشترك عادي' },
  { id:'vip', label:'مشترك VIP' },
  { id:'owner', label:'المالك' }
];

export const PERMISSIONS = {
  visitor: { browse:true, watchFree:true, download:false, contribute:false, manage:false },
  free:    { browse:true, watchFree:true, download:true, contribute:true, manage:false },
  vip:     { browse:true, watchFree:true, download:true, contribute:true, manage:false },
  owner:   { browse:true, watchFree:true, download:true, contribute:true, manage:true }
};
