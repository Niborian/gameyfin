export {useAuth, AuthProvider} from './SessionAuthProvider';

export function getCsrfToken() {
    const token = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
    return token || '';
}