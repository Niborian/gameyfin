import {useAuth} from "Frontend/util/auth";
import {useEffect, useRef, useState} from "react";
import {Button, Card, CardBody, CardHeader, Link, useDisclosure} from "@heroui/react";
import {Form, Formik} from "formik";
import Input from "Frontend/components/general/input/Input";
import PasswordResetModal from "Frontend/components/general/modals/PasswordResetModal";
import SignUpModal from "Frontend/components/general/modals/SignUpModal";
import {RegistrationEndpoint} from "Frontend/generated/endpoints";
import {loginIsReady} from "Frontend/util/startupLifecycle";

export default function LoginView() {
    const {state, login} = useAuth();

    const passwordResetModal = useDisclosure();
    const signUpModal = useDisclosure();

    const [signUpAllowed, setSignUpAllowed] = useState<boolean>(false);
    const [policySettled, setPolicySettled] = useState(false);
    const [loginCompletion, setLoginCompletion] = useState(0);
    const ready = loginIsReady(state.initializing, state.loading, policySettled);
    const redirectOwner = useRef({mounted: false, interactive: false, pending: false, redirected: false,
        completed: false, destination: undefined as string | undefined, sessionFailure: undefined as (() => void) | undefined});

    useEffect(() => {
        redirectOwner.current.mounted = true;
        return () => { redirectOwner.current.mounted = false; };
    }, []);

    useEffect(() => {
        let current = true;
        if (state.user) {
            // An interactive submit owns its saved/default destination, even if
            // LOGIN_SUCCESS updates state before login() returns that metadata.
            if (!redirectOwner.current.interactive) redirectAfterLogin();
        } else {
            setPolicySettled(false);
            RegistrationEndpoint.isSelfRegistrationAllowed()
                .then(allowed => { if (current) setSignUpAllowed(allowed); })
                // Registration availability must not prevent ordinary login.
                .catch(() => { if (current) setSignUpAllowed(false); })
                .finally(() => { if (current) setPolicySettled(true); });
        }
        return () => { current = false; };
    }, [state.user]);

    useEffect(() => {
        const owner = redirectOwner.current;
        if (!owner.mounted || !owner.completed || state.initializing || state.loading) return;
        owner.completed = false;
        owner.pending = false;
        const sessionFailure = owner.sessionFailure;
        owner.sessionFailure = undefined;
        if (state.user) {
            redirectAfterLogin(owner.destination);
        } else {
            owner.interactive = false;
            sessionFailure?.();
        }
    }, [state.user, state.initializing, state.loading, loginCompletion]);

    async function tryLogin(values: any, formik: any) {
        const owner = redirectOwner.current;
        if (!ready || !owner.mounted || owner.pending || owner.redirected) return;
        owner.interactive = true;
        owner.pending = true;
        owner.completed = false;
        owner.destination = undefined;
        owner.sessionFailure = () => {
            formik.setFieldError("username", " ");
            formik.setFieldError("password", "Could not verify your session. Please try again.");
        };
        try {
            // Capture Hilla's normalized destination without unloading the page
            // that owns its awaited post-login profile request.
            const {defaultUrl, error, redirectUrl} = await login(values.username, values.password, {
                navigate: destination => { if (owner.mounted) owner.destination = destination; }
            });
            if (!owner.mounted) return;
            if (error) {
                owner.interactive = false;
                owner.sessionFailure = undefined;
                formik.setFieldError("username", " "); // Mark the field red, but don't show an error message
                formik.setFieldError("password", "Invalid username and/or password.");
            } else {
                owner.destination ??= redirectUrl ?? defaultUrl ?? '/';
                owner.completed = true;
                // Hilla can return error:false even after a null/401 profile.
                // Let the effect inspect rendered auth state, not this closure.
                setLoginCompletion(value => value + 1);
            }
        } catch (error) {
            owner.interactive = false;
            owner.sessionFailure = undefined;
            throw error;
        } finally {
            if (!owner.completed) owner.pending = false;
        }
    }

    function redirectAfterLogin(redirectUrl?: string, defaultUrl?: string) {
        const owner = redirectOwner.current;
        if (!owner.mounted || owner.redirected) return;
        owner.redirected = true;
        window.location.href = redirectUrl ?? defaultUrl ?? '/';
    }

    return (
        <div className="flex size-full gradient-primary">
            <Card className="m-auto p-12">
                <CardHeader>
                    <img
                        className="h-28 w-full content-center"
                        src="/images/Logo.svg"
                        alt="Gameyfin Logo"
                    />
                </CardHeader>
                <CardBody className="mt-8 mb-2 w-80 max-w-(--breakpoint-lg) sm:w-96">
                    <Formik
                        initialValues={{}}
                        onSubmit={tryLogin}>
                        {(formik: { isSubmitting: any; }) => (
                            <Form className="mb-1 flex flex-col gap-6">
                                <Input
                                    name="username"
                                    label="Username"
                                    autoComplete="username"
                                />
                                <Input
                                    name="password"
                                    label="Password"
                                    autoComplete="current-password"
                                    type="password"
                                />
                                <div className="flex justify-between items-center">
                                    <Link color="foreground" underline="always" href="#"
                                          onPress={passwordResetModal.onOpen}>
                                        Forgot password?
                                    </Link>
                                    <div className="flex flex-row gap-2">
                                        {signUpAllowed &&
                                            <Button color="default" variant="light"
                                                    onPress={signUpModal.onOpen}>
                                                Sign up
                                            </Button>
                                        }
                                        <Button color="primary" type="submit" isDisabled={!ready} isLoading={formik.isSubmitting}>
                                            {formik.isSubmitting ? "" : "Log in"}
                                        </Button>
                                    </div>
                                </div>
                            </Form>
                        )}
                    </Formik>
                </CardBody>
            </Card>

            <PasswordResetModal isOpen={passwordResetModal.isOpen} onOpenChange={passwordResetModal.onOpenChange}/>
            <SignUpModal isOpen={signUpModal.isOpen} onOpenChange={signUpModal.onOpenChange}/>
        </div>
    );
}
