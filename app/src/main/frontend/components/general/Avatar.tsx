import {useAuth} from "Frontend/util/auth";
import {Avatar as NextUiAvatar, type AvatarProps as NextUiAvatarProps} from "@heroui/react";
import {avatarSource, type AvatarMetadata} from "Frontend/util/avatarMetadata";
import {useAvatarMetadata} from "Frontend/components/AvatarMetadataProvider";

type AvatarProps = Omit<NextUiAvatarProps, 'src'> & Omit<AvatarMetadata, 'id'>;

export default function Avatar({username, hasAvatar, avatarVersion, avatarId, ...props}: AvatarProps) {
    const auth = useAuth();
    const metadata = useAvatarMetadata() ?? auth.state.user;
    const src = avatarSource(metadata, {username, hasAvatar, avatarVersion, avatarId});
    return <NextUiAvatar {...props} showFallback src={src}/>;
}
