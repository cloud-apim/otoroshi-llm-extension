class AudioModelsPage extends Component {
    state = {
        dynamicVoices: null
    }
    formSchema = (state) => ({
        _loc: {
            type: 'location',
            props: {},
        },
        id: {type: 'string', disabled: true, props: {label: 'Id', placeholder: '---'}},
        name: {
            type: 'string',
            props: {label: 'Name', placeholder: 'My Awesome Context'},
        },
        description: {
            type: 'string',
            props: {label: 'Description', placeholder: 'Description of the Context'},
        },
        metadata: {
            type: 'object',
            props: {label: 'Metadata'},
        },
        tags: {
            type: 'array',
            props: {label: 'Tags'},
        },
        provider: {
            'type': 'select',
            props: {
                label: 'Provider', possibleValues: _.sortBy([
                    {label: 'OpenAI', value: "openai"},
                    {label: 'Groq', value: "groq"},
                    {label: 'ElevenLabs', value: "elevenlabs"}
                ], i => i.label)
            }
        },
        mode: {
            'type': 'select',
            props: {
                label: 'Mode', possibleValues: _.sortBy([
                    // {label: 'Transcription', value: "transcription"},
                    {label: 'Text to speech', value: "tts"},
                ], i => i.label)
            }
        },
        voice: state?.provider === 'openai' ? {
            'type': 'select',
            props: {
                label: 'Voice', possibleValues: _.sortBy([
                    {label: 'Alloy', value: "alloy"},
                    {label: 'Sage', value: "sage"},
                    {label: 'Ash', value: "Ash"},
                    {label: 'Ballad', value: "ballad"},
                    {label: 'Corad', value: "corad"},
                    {label: 'Echo', value: "echo"},
                    {label: 'Fable', value: "fable"},
                    {label: 'Onyx', value: "onyx"},
                    {label: 'Nova', value: "nova"},
                ], i => i.label)
            }
        } : state?.provider === 'groq' ? {
                'type': 'select',
                props: {
                    label: 'Voice', possibleValues: _.sortBy([
                        {label: 'Arista-PlayAI', value: "arista-PlayAI"},
                        {label: 'Atlas-PlayAI', value: "Atlas-PlayAI"},
                        {label: 'Basil-PlayAI', value: "Basil-PlayAI"},
                        {label: 'Briggs-PlayAI,', value: "Briggs-PlayAI,"},
                    ], i => i.label)
                }
            } :
            state?.provider === 'elevenlab' ?
                {
                    'type': 'select',
                    props: {
                        label: 'Voice', possibleValues: _.sortBy([
                            {label: 'Aria', value: "9BWtsMINqrJLrRacOk9x"},
                            {label: 'Roger', value: "CwhRBWXzGAHq8TQ4Fs17"},
                        ], i => i.label)
                    }
                } :
                {
                    'type': 'select',
                    props: {
                        label: 'Voice', possibleValues: _.sortBy([
                            {label: 'Alloy', value: "alloy"},
                        ], i => i.label)
                    }
                },
        config: {
            type: "jsonobjectcode",
            props: {
                label: 'Configuration'
            }
        }
    });


    fetchVoices = (audioModel, force) => {
        console.log("got provider from  fetchVoices  = ", audioModel)
        fetch(`/extensions/cloud-apim/extensions/ai-extension/audio-models/_voices`, {
            method: 'POST',
            credentials: 'include',
            headers: {
                Accept: 'application/json',
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(audioModel)
        }).then(r => {
            if (r.status === 200) {
                r.json().then(body => {
                    if (body.done) {
                        console.log("fetching new Voices...", body.voices)
                        this.setState({ dynamicVoices: body.voices })
                    }
                })
            }
        })
    }

    columns = [
        {
            title: 'Name',
            filterId: 'name',
            content: (item) => item.name,
        },
        {
            title: 'Description',
            filterId: 'description',
            content: (item) => item.description,
        },
        {
            title: 'Provider',
            filterId: 'provider',
            content: (item) => item.provider,
        },
    ];


    formFlow = (state) => {
        console.log("statttee = ", state)
        if (!state.provider) {
            return [
                '_loc', 'id', 'name', 'description', 'tags', 'metadata', '---', 'provider', 'mode']
        }
        return [
            '_loc', 'id', 'name', 'description', 'tags', 'metadata', '---', 'provider', 'mode', 'voice', 'config']
    };

    componentDidMount() {
        this.props.setTitle(`Audio models`);
    }

    client = BackOfficeServices.apisClient('ai-gateway.extensions.cloud-apim.com', 'v1', 'audio-models');

    render() {
        const formSchemaBuilder = this.formSchema(this.state || {});
        const formFlowBuilder = this.formFlow(this.state || {});
        return (
            React.createElement(Table, {
                parentProps: this.props,
                selfUrl: "extensions/cloud-apim/ai-gateway/audio-models",
                defaultTitle: "All Audio models",
                defaultValue: () => ({
                    id: 'audio-model_' + uuid(),
                    name: 'Audio model',
                    description: 'An audio model',
                    tags: [],
                    metadata: {},
                    mode: "tts"
                }),
                onStateChange: (state, oldState, update) => {
                    this.setState(state)

                    if (!_.isEqual(state.voice, oldState.voice)) {
                        console.log("NEW state.voice", state.voice)
                    }

                    if (!_.isEqual(state.provider, oldState.provider)) {
                        console.log("set default value", state.provider);

                        this.fetchVoices(state, false);

                        if (state.provider === 'openai') {
                            update({
                                id: state.id,
                                name: state.name,
                                description: state.description,
                                tags: state.tags,
                                metadata: state.metadata,
                                mode: state.mode,
                                voice: null,
                                provider: 'openai',
                                config: {
                                    connection: {
                                        token: 'xxxxx',
                                        timeout: 30000,
                                    },
                                    options: state.mode === 'tts' ? {
                                            model: 'gpt-4o-mini-tts',
                                            response_format: 'mp3',
                                            speed: 1
                                        }
                                        :
                                        {
                                            model: 'gpt-4o-mini-transcribe',
                                        }
                                }
                            });
                        } else if (state.provider === 'groq') {
                            update({
                                id: state.id,
                                name: state.name,
                                description: state.description,
                                tags: state.tags,
                                metadata: state.metadata,
                                mode: state.mode,
                                provider: 'groq',
                                voice: null,
                                config: {
                                    connection: {
                                        token: 'xxxxx',
                                        timeout: 30000,
                                    },
                                    options: state.mode === 'tts' ? {
                                            model: 'playai-tts',
                                            response_format: 'wav'
                                        } :
                                        {
                                            model: 'whisper-large-v3-turbo',
                                        }
                                }
                            });
                        } else if (state.provider === 'elevenlabs') {
                            update({
                                id: state.id,
                                name: state.name,
                                description: state.description,
                                tags: state.tags,
                                metadata: state.metadata,
                                mode: state.mode,
                                voice: null,
                                provider: 'elevenlabs',
                                config: {
                                    connection: {
                                        token: 'xxxxx',
                                        timeout: 30000,
                                    },
                                    options: state.mode === 'tts' ? {
                                        model_id: 'eleven_multilingual_v2',
                                        output_format: 'mp3_44100_128'
                                    } : {}
                                }
                            });
                        }
                    }
                },
                itemName: "Audio model",
                formSchema: formSchemaBuilder,
                formFlow: formFlowBuilder,
                columns: this.columns,
                stayAfterSave: true,
                fetchItems: (paginationState) => this.client.findAll(),
                updateItem: this.client.update,
                deleteItem: this.client.delete,
                createItem: this.client.create,
                navigateTo: (item) => {
                    window.location = `/bo/dashboard/extensions/cloud-apim/ai-gateway/audio-models/edit/${item.id}`
                },
                itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/ai-gateway/audio-models/edit/${item.id}`,
                showActions: true,
                showLink: true,
                rowNavigation: true,
                extractKey: (item) => item.id,
                export: true,
                kubernetesKind: "ai-gateway.extensions.cloud-apim.com/AudioModel"
            }, null)
        );
    }
}