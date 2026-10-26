import {useLayoutEffect, useRef, useState} from "react";
import {
    Button,
    ButtonGroup,
    Dropdown,
    DropdownItem,
    DropdownMenu,
    DropdownTrigger,
    SharedSelection
} from "@heroui/react";
import { CaretDownIcon } from "@phosphor-icons/react";
import {useUserPreferenceService} from "Frontend/util/user-preference-service";
import {useAuth} from "Frontend/util/auth";

export interface ComboButtonOption {
    label: string;
    description: string;
    action: () => void;
    isDisabled?: boolean;
}

export interface ComboButtonProps {
    description?: string;
    options: Record<string, ComboButtonOption>;
    preferredOptionKey?: string;
}

export default function ComboButton({options, preferredOptionKey, description}: ComboButtonProps) {
    type Owner = {identity: string; active: boolean; manual: boolean};
    const auth = useAuth();
    const identity = JSON.stringify([preferredOptionKey, auth.state.user?.id, auth.state.user?.username,
        auth.state.user?.roles, auth.state.loading, auth.state.initializing]);
    const ownerRef = useRef<Owner | undefined>(undefined);
    const [selection, setSelection] = useState<{owner?: Owner; key?: string}>({});
    const desired = selection.owner?.identity === identity && selection.owner.active ? selection.key : undefined;
    // Keep a saved key even while its optional capability is unavailable. Ordinary
    // options remain usable, and a later append can restore the saved preference.
    const selectedOptionValue = desired && options[desired] ? desired : Object.keys(options)[0];
    const selectedOption = new Set(selectedOptionValue ? [selectedOptionValue] : []);
    const userPreferenceService = useUserPreferenceService();

    useLayoutEffect(() => {
        const owner = {identity, active: true, manual: false};
        ownerRef.current = owner;
        setSelection({owner});
        if (preferredOptionKey && !auth.state.loading && !auth.state.initializing) {
            void Promise.resolve().then(() => {
                if (!owner.active) return undefined;
                return userPreferenceService.get(preferredOptionKey);
            }).then(key => {
                if (owner.active && ownerRef.current === owner && !owner.manual) setSelection({owner, key});
            }).catch(() => { /* A preference failure must not hide ordinary options. */ });
        }
        return () => {owner.active = false; if (ownerRef.current === owner) ownerRef.current = undefined;};
        // The service object is recreated each render; identity owns this lookup.
    }, [identity]);

    function onSelectionChange(keys: SharedSelection) {
        const key = keys.currentKey, owner = ownerRef.current;
        if (!key || !options[key] || !owner?.active || owner.identity !== identity) return;
        owner.manual = true;
        setSelection({owner, key}); // Never wait for persistence before updating the UI.
        if (preferredOptionKey) {
            void Promise.resolve().then(() => {
                if (owner.active && ownerRef.current === owner) return userPreferenceService.set(preferredOptionKey, key);
                return undefined;
            }).catch(() => { /* Keep the explicit choice if persistence fails. */ });
        }
    }

    return options[selectedOptionValue] && (
        <ButtonGroup className="gap-px">
            <Button color="primary" className="w-52"
                    onPress={options[selectedOptionValue].action}>
                <div className="flex flex-col items-center">
                    <p className="font-semibold">{options[selectedOptionValue].label}</p>
                    <p className="text-xs font-normal opacity-70 ">{description}</p>
                </div>
            </Button>
            <Dropdown placement="bottom-end">
                <DropdownTrigger>
                    <Button isIconOnly color="primary">
                        <CaretDownIcon/>
                    </Button>
                </DropdownTrigger>
                <DropdownMenu
                    disallowEmptySelection
                    aria-label="Merge options"
                    selectedKeys={selectedOption}
                    selectionMode="single"
                    onSelectionChange={onSelectionChange}
                    className="w-60"
                >
                    {Object.entries(options).map(([key, option]) => (
                        <DropdownItem key={key} description={option.description}>
                            {option.label}
                        </DropdownItem>
                    ))}
                </DropdownMenu>
            </Dropdown>
        </ButtonGroup>
    );
}
