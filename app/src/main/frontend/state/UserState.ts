import {proxy} from "valtio";
import {runStartupState} from "Frontend/util/startupLifecycle";
import {UserEndpoint} from "Frontend/generated/endpoints";
import ExtendedUserInfoDto from "Frontend/generated/org/gameyfin/app/users/dto/ExtendedUserInfoDto";

type UserState = {
    isLoaded: boolean;
    state: Record<number, ExtendedUserInfoDto>;
    users: ExtendedUserInfoDto[];
};

export const userState = proxy<UserState>({
    isLoaded: false,
    state: {},
    get users() {
        return Object.values<ExtendedUserInfoDto>(this.state);
    }
});

/** Fetch and cache all users **/
export async function initializeUserState(signal?: AbortSignal) {
    return runStartupState(userState, signal, () => { userState.state = {}; userState.isLoaded = false; }, async current => {
        if (!current() || userState.isLoaded) return;

        try {
            const allUsers = await UserEndpoint.getAllUsers(signal ? {signal} : undefined);
            if (!current()) return;
            allUsers.forEach((user: ExtendedUserInfoDto) => {
                userState.state[user.id] = user;
            });
            userState.isLoaded = true;
        } catch (error) {
            if (!current()) return;
            console.error("Failed to load users:", error);
        }
    });
}
