export const ROLES = [
  { id: "visitor", label: "زائر" },
  { id: "free", label: "مشترك عادي" },
  { id: "vip", label: "مشترك VIP" },
  { id: "owner", label: "المالك" }
];

// الصلاحيات مستقلة لكل فئة.
// لا نستخدم "inherits" حتى لا ترث فئة صلاحيات فئة أخرى بالخطأ.
export const PERMISSIONS = {
  visitor: {
    browse: true,
    watch: false,
    useTranslations: false,
    manageMovies: false,
    manageUsers: false,
    manageSettings: false
  },
  free: {
    browse: true,
    watch: true,
    useTranslations: true,
    manageMovies: false,
    manageUsers: false,
    manageSettings: false
  },
  vip: {
    browse: true,
    watch: true,
    useTranslations: true,
    manageMovies: false,
    manageUsers: false,
    manageSettings: false
  },
  owner: {
    browse: true,
    watch: true,
    useTranslations: true,
    manageMovies: true,
    manageUsers: true,
    manageSettings: true
  }
};

export function hasPermission(role, permission) {
  return Boolean(PERMISSIONS[role]?.[permission]);
}
