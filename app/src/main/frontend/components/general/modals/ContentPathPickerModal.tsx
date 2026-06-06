import React, {useState} from "react";
import {Button, Input, Modal, ModalBody, ModalContent, ModalFooter, ModalHeader} from "@heroui/react";
import FileTreeView from "Frontend/components/general/input/FileTreeView";

interface ContentPathPickerModalProps {
    returnSelectedPath: (path: string) => void;
    isOpen: boolean;
    onOpenChange: () => void;
}

export default function ContentPathPickerModal({
                                                   returnSelectedPath,
                                                   isOpen,
                                                   onOpenChange
                                               }: ContentPathPickerModalProps) {
    const [selectedPath, setSelectedPath] = useState("");

    return (
        <Modal isOpen={isOpen} onOpenChange={onOpenChange} backdrop="opaque" size="3xl">
            <ModalContent>
                {(onClose) => (
                    <>
                        <ModalHeader className="flex flex-col gap-1">Select content path</ModalHeader>
                        <ModalBody>
                            <Input
                                label="Selected path"
                                value={selectedPath}
                                onValueChange={setSelectedPath}
                                placeholder="/mnt/Games/Game/DLC"
                            />
                            <div className="h-72 overflow-auto">
                                <FileTreeView includeFiles onPathChange={setSelectedPath}/>
                            </div>
                        </ModalBody>
                        <ModalFooter>
                            <Button variant="light" onPress={onClose}>
                                Cancel
                            </Button>
                            <Button
                                color="primary"
                                isDisabled={!selectedPath.trim()}
                                onPress={() => {
                                    returnSelectedPath(selectedPath.trim());
                                    setSelectedPath("");
                                    onClose();
                                }}
                            >
                                Select
                            </Button>
                        </ModalFooter>
                    </>
                )}
            </ModalContent>
        </Modal>
    );
}
