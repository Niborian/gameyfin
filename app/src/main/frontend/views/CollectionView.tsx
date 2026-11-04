import {useSnapshot} from "valtio/react";
import {gameState} from "Frontend/state/GameState";
import React, {useEffect} from "react";
import {useNavigate, useParams} from "react-router";
import CoverGrid from "Frontend/components/general/covers/CoverGrid";
import {collectionState} from "Frontend/state/CollectionState";

export default function CollectionView() {
    const {collectionId} = useParams();
    const navigate = useNavigate();
    const collections = useSnapshot(collectionState);
    const gameSnapshot = useSnapshot(gameState);
    const games = collectionId ? gameSnapshot.gamesByCollectionId[parseInt(collectionId)] || [] : [];
    const ready = collections.isLoaded && gameSnapshot.isLoaded;

    useEffect(() => {
        window.scrollTo(0, 0)
    }, [])

    useEffect(() => {
        if (collections.isLoaded && (!collectionId || !collections.state[parseInt(collectionId)])) {
            navigate("/", {replace: true});
        }
        document.title = collections.state[parseInt(collectionId!)]?.name || "Gameyfin";
    }, [collectionId, collections]);

    return (
        <div className="flex flex-col gap-6">
            <p className="text-4xl font-bold text-center">{collections.state[parseInt(collectionId!)]?.name}</p>
            {ready ? <>
                <CoverGrid games={games}/>
                {games.length === 0 && <p className="text-center text-gray-500">This collection is empty.</p>}
            </> : <p role="status" className="text-center text-gray-500">Loading collection…</p>}
        </div>
    );
}