import {proxy} from "valtio";
import {startupIsCurrent, ownStartupState} from "Frontend/util/startupLifecycle";
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
    if (!startupIsCurrent(signal) || userState.isLoaded) return;
    ownStartupState(userState, signal, () => { userState.state = {}; userState.isLoaded = false; });

    try {
        const allUsers = await UserEndpoint.getAllUsers(signal ? {signal} : undefined);
        if (!startupIsCurrent(signal)) return;
        allUsers.forEach((user: ExtendedUserInfoDto) => {
            userState.state[user.id] = user;
        });
        userState.isLoaded = true;
    } catch (error) {
        if (!startupIsCurrent(signal)) return;
        console.error("Failed to load users:", error);
    }
}
