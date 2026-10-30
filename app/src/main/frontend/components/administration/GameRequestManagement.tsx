import React from "react";
import withConfigPage from "Frontend/components/administration/withConfigPage";
import * as Yup from 'yup';
import ConfigFormField from "Frontend/components/administration/ConfigFormField";
import Section from "Frontend/components/general/Section";
import {Button} from "@heroui/react";
import {useNavigate} from "react-router";
import SteamUpdateInbox from "Frontend/components/administration/SteamUpdateInbox";

function GameRequestManagementLayout({getConfig, formik}: any) {
    const navigate = useNavigate();

    return (
        <div className="flex flex-col">
            <div className="flex flex-row">
                <div className="flex flex-col flex-1">
                    <Section title="Game requests configuration"/>
                    <ConfigFormField configElement={getConfig("requests.games.enabled")}/>

                    <Section title="Permissions"/>
                    <div className="flex flex-row items-center gap-4">
                        <ConfigFormField
                            configElement={getConfig("requests.games.allow-guests-to-request-games")}
                            isDisabled={!formik.values.security["allow-public-access"]}/>
                        <ConfigFormField configElement={getConfig("requests.games.max-open-requests-per-user")}/>
                    </div>

                    <Button onPress={() => navigate("/requests")}>
                        Manage game requests
                    </Button>
                    <Section title="Authorized acquisition scope"/>
                    <p className="text-default-500 text-sm mb-3">Prowlarr/qBittorrent acquisition requires a separately configured isolated provider. These IDs allow indexers; saving them does not enable downloads. Review active provider status in Manage game requests.</p>
                    <ConfigFormField configElement={getConfig("requests.acquisition.approved-indexer-ids")}/>
                    <Section title="Game update notices"/>
                    <ConfigFormField configElement={getConfig("requests.steam-updates.enabled")}/>
                    <SteamUpdateInbox/>
                </div>
            </div>
        </div>
    );
}

const validationSchema = Yup.object({
    requests: Yup.object({
        acquisition: Yup.object({
            "approved-indexer-ids": Yup.array().of(Yup.string().matches(/^[1-9][0-9]*$/, "Use a positive Prowlarr indexer ID").required()).required(),
        }).required(),
        games: Yup.object({
            enabled: Yup.boolean().required("Required"),
            "allow-guests-to-request-games": Yup.boolean().required("Required"),
            "max-open-requests-per-user": Yup.number()
                .min(0, "Must be at least 0")
                .max(Number.MAX_SAFE_INTEGER, `Must be lower than ${Number.MAX_SAFE_INTEGER}`)
                .required("Required"),
        }).required("Required"),
    }).required("Required"),
});

export const GameRequestManagement = withConfigPage(GameRequestManagementLayout, "Game Requests", validationSchema);
